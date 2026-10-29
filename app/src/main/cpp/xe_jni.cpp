// xe_jni.cpp —— libarchive 双向绑定（native 引擎 v1）。
//
// 设计契约（引擎设计 v1.2 §3 + v1.3 路由修订）：
//   - 无会话状态：每次调用 dup(fd) 后重开 archive，用毕即关（fd 所有权归调用方）
//   - 批量数据不跨 JNI：写循环在 native 完成（archive_read_data_block → write(fd)）
//   - 中止检查点：每个数据块边界回调 shouldAbort()
//   - 双计数器：input = archive_filter_bytes 差分；output = 实际 write 字节
//   - 路径守卫在 native 拼接层执行（与 Kotlin PathGuard 同规则，防 Zip Slip）
//   - CRC：libarchive 读取期自动校验（zip/7z），失败以 READ/FORMAT 码上报

#include <jni.h>
#include <archive.h>
#include <archive_entry.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <fcntl.h>

#define LOG_TAG "xe"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 与 Kotlin EngineErrorCode 对齐
constexpr jint XE_OK = 0;
constexpr jint XE_ABORTED = 1;
constexpr jint XE_ERR_FORMAT = 5;
constexpr jint XE_ERR_READ = 6;
constexpr jint XE_ERR_WRITE = 7;
constexpr jint XE_ERR_ENCRYPTED = 2; // 与 Kotlin EngineErrorCode.ENCRYPTED 对齐
constexpr jint XE_ERR_LIMIT = 13;

// Kotlin 侧 ArchiveFormat 映射码
constexpr jint FMT_UNKNOWN = 0;
constexpr jint FMT_ZIP = 1;
constexpr jint FMT_SEVEN_Z = 2;
constexpr jint FMT_TAR = 3;
constexpr jint FMT_CPIO = 4;
constexpr jint FMT_AR = 5;
constexpr jint FMT_ISO = 6;
constexpr jint FMT_CAB = 7;
constexpr jint FMT_RAR = 8;
constexpr jint FMT_LHA = 9;
constexpr jint FMT_XAR = 10;
constexpr jint FMT_GZ = 11;   // 纯 gzip 流（filter）
constexpr jint FMT_RAW = 12;  // 裸 deflate 等单文件流

thread_local char g_lastError[256] = {0};

void setErr(const char* msg) { strncpy(g_lastError, msg, sizeof(g_lastError) - 1); }

template <size_t N>
jlongArray toLongArray(JNIEnv* env, const jlong (&vals)[N]) {
    jlongArray out = env->NewLongArray((jsize) N);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, (jsize) N, vals);
    return out;
}

struct Cb {
    JNIEnv* env;
    jobject obj;
    jmethodID onEntryStart;   // (JLjava/lang/String;JZ)I   fd≥0 写入；-1 跳过；-2 目录已处理
    jmethodID onEntryDone;    // (JLjava/lang/String;J)V
    jmethodID onEntryAborted; // (JLjava/lang/String;J)V
    jmethodID onEntrySkipped; // (JLjava/lang/String;I)V  reason: 1=unsafe 2=skipBits 3=data
    jmethodID onInput;        // (J)V
    jmethodID onOutput;       // (J)V
    jmethodID shouldAbort;    // ()Z
};

bool cacheMethods(JNIEnv* env, jobject callbacks, Cb& cb) {
    jclass cls = env->GetObjectClass(callbacks);
    struct { const char* name; const char* sig; jmethodID* out; } methods[] = {
        {"onEntryStart",   "(JLjava/lang/String;JZ)I", &cb.onEntryStart},
        {"onEntryDone",    "(JLjava/lang/String;J)V",  &cb.onEntryDone},
        {"onEntryAborted", "(JLjava/lang/String;J)V",  &cb.onEntryAborted},
        {"onEntrySkipped", "(JLjava/lang/String;I)V",  &cb.onEntrySkipped},
        {"onInputBytes",   "(J)V",                     &cb.onInput},
        {"onOutputBytes",  "(J)V",                     &cb.onOutput},
        {"shouldAbort",    "()Z",                      &cb.shouldAbort},
    };
    for (auto& m : methods) {
        *m.out = env->GetMethodID(cls, m.name, m.sig);
        if (*m.out == nullptr) {
            LOGE("GetMethodID failed: %s", m.name);
            return false;
        }
    }
    return true;
}

// 路径守卫：分隔符统一、剥盘符/前导斜杠、拒绝 ".."、控制字符、超长；与 Kotlin PathGuard 同规则
bool sanitizePath(const char* in, char* out, size_t outCap) {
    size_t n = strlen(in);
    if (n == 0 || n >= outCap) return false;
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char) in[i];
        if (c < 0x20 || c == 0x7F) return false;
    }
    size_t o = 0;
    size_t segStart = 0;
    for (size_t i = 0; i <= n; i++) {
        char c = (i < n) ? in[i] : '/';
        bool sep = (c == '/' || c == '\\');
        if (!sep && i < n) continue;
        size_t len = i - segStart;
        const char* seg = in + segStart;
        if (len == 0 || (len == 1 && seg[0] == '.')) { segStart = i + 1; continue; }
        if (len == 2 && seg[0] == '.' && seg[1] == '.') return false;
        if (len == 2 && ((seg[0] >= 'A' && seg[0] <= 'Z') || (seg[0] >= 'a' && seg[0] <= 'z')) && seg[1] == ':') {
            segStart = i + 1;  // 盘符段丢弃
            continue;
        }
        if (o > 0) {
            if (o + 1 >= outCap) return false;
            out[o++] = '/';
        }
        if (o + len >= outCap) return false;
        memcpy(out + o, seg, len);
        o += len;
        segStart = i + 1;
    }
    out[o] = 0;
    return o > 0;
}

jint mapFormat(struct archive* a) {
    // 格式码非互斥位段（如 7ZIP=0xE0000 与 ZIP 掩码相交），必须按 BASE_MASK 等值比较
    int base = archive_format(a) & ARCHIVE_FORMAT_BASE_MASK;
    if (base == ARCHIVE_FORMAT_ZIP) return FMT_ZIP;
    if (base == ARCHIVE_FORMAT_7ZIP) return FMT_SEVEN_Z;
    if (base == ARCHIVE_FORMAT_TAR) return FMT_TAR;
    if (base == ARCHIVE_FORMAT_CPIO) return FMT_CPIO;
    if (base == ARCHIVE_FORMAT_AR) return FMT_AR;
    if (base == ARCHIVE_FORMAT_ISO9660) return FMT_ISO;
    if (base == ARCHIVE_FORMAT_CAB) return FMT_CAB;
    if (base == ARCHIVE_FORMAT_RAR || base == ARCHIVE_FORMAT_RAR_V5) return FMT_RAR;
    if (base == ARCHIVE_FORMAT_LHA) return FMT_LHA;
    if (base == ARCHIVE_FORMAT_XAR) return FMT_XAR;
    int flt = archive_filter_code(a, 0);
    if (flt == ARCHIVE_FILTER_GZIP) return FMT_GZ;
    return FMT_RAW;
}

// 打开一个 archive 读取会话（dup fd、从头读）。返回 dup 出的 fd，*-1 失败。
// passphrase 必须在 open 之前注册（archive 状态机要求 'new' 态），加密 zip/rar5 由此解密。
int openArchive(int srcFd, struct archive** outAr, const char* passphrase) {
    int fd = dup(srcFd);
    if (fd < 0) { setErr("dup failed"); return -1; }
    if (lseek(fd, 0, SEEK_SET) != 0) { setErr("lseek failed"); close(fd); return -1; }
    struct archive* a = archive_read_new();
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    if (passphrase != nullptr && passphrase[0] != 0) {
        archive_read_add_passphrase(a, passphrase);
    }

    if (archive_read_open_fd(a, fd, 64 * 1024) != ARCHIVE_OK) {
        setErr(archive_error_string(a) ? archive_error_string(a) : "open failed");
        archive_read_free(a);
        close(fd);
        return -1;
    }
    *outAr = a;
    return fd;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_limao_unzip_native_XeBridge_nativeProbe(JNIEnv* env, jobject, jint srcFd) {
    struct archive* a = nullptr;
    int fd = openArchive(srcFd, &a, nullptr);
    if (fd < 0) { LOGE("probe: openArchive failed: %s", g_lastError); return FMT_UNKNOWN; }    struct archive_entry* entry = archive_entry_new();
    jint result = FMT_UNKNOWN;
    int r = archive_read_next_header2(a, entry);
    if (r == ARCHIVE_OK || r == ARCHIVE_WARN) {
        result = mapFormat(a);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "probe: fmt=0x%x filter=%d -> %d",
                            archive_format(a), archive_filter_code(a, 0), result);
    } else if (archive_filter_code(a, 0) == ARCHIVE_FILTER_GZIP) {
        result = FMT_GZ;
    } else {
        setErr(archive_error_string(a) ? archive_error_string(a) : "probe failed");
        LOGE("probe: next_header r=%d err=%s", r, archive_error_string(a) ? archive_error_string(a) : "?");
    }
    archive_entry_free(entry);
    archive_read_free(a);
    close(fd);
    return result;
}

struct ScanCb {
    JNIEnv* env;
    jobject obj;
    jmethodID onEntry; // (JLjava/lang/String;JJJZJ)V  index,path,uncomp,comp,isDir,mtime
};

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_limao_unzip_native_XeBridge_nativeScan(JNIEnv* env, jobject, jint srcFd, jobject sink) {
    jlong ret[3] = {0, 0, 0}; // entryCount, totalUncompressed, anyEncrypted
    jclass cls = env->GetObjectClass(sink);
    jmethodID onEntry = env->GetMethodID(cls, "onEntry", "(JLjava/lang/String;JJZJ)V");
    if (onEntry == nullptr) { setErr("scan sink method missing"); return env->NewLongArray(0); }

    struct archive* a = nullptr;
    int fd = openArchive(srcFd, &a, nullptr);
    if (fd < 0) return env->NewLongArray(0);
    struct archive_entry* entry = archive_entry_new();
    int64_t index = 0;
    bool fail = false;
    while (true) {
        int r = archive_read_next_header2(a, entry);
        if (r == ARCHIVE_EOF) break;
        if (r == ARCHIVE_FATAL) {
            setErr(archive_error_string(a) ? archive_error_string(a) : "scan fatal");
            fail = true;
            break;
        }
        const char* path = archive_entry_pathname_utf8(entry);
        if (path == nullptr) path = archive_entry_pathname(entry);
        jstring jpath = env->NewStringUTF(path ? path : "");
        env->CallVoidMethod(sink, onEntry,
                            (jlong) index, jpath,
                            (jlong) archive_entry_size(entry),
                            (jlong) -1, // compressed 未知（libarchive 不提供单条目压缩量）
                            (jboolean) archive_entry_filetype(entry) == AE_IFDIR,
                            (jlong) (archive_entry_mtime(entry) * 1000LL));
        env->DeleteLocalRef(jpath);
        if (env->ExceptionCheck()) { fail = true; break; }
        // RAR3/RAR5 只置 is_data_encrypted（3.6+ 新旗标），必须与旧 is_encrypted 合并判断
        if (archive_entry_is_encrypted(entry) || archive_entry_is_data_encrypted(entry)) ret[2] = 1;
        int64_t sz = archive_entry_size_is_set(entry) ? archive_entry_size(entry) : 0;
        if (sz > 0) ret[1] += sz;
        ret[0]++;
        index++;
    }
    archive_entry_free(entry);
    archive_read_free(a);
    close(fd);
    if (fail) return env->NewLongArray(0);
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, ret);
    return out;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_limao_unzip_native_XeBridge_nativeExtract(JNIEnv* env, jobject, jint srcFd,
                                                    jobject callbacks, jlongArray skipBits,
                                                    jboolean ignoreEntryErrors, jstring jpassphrase) {
    jlong ret[3] = {(jlong) XE_ERR_FORMAT, 0, 0}; // code, entriesDone, bytesDone
    Cb cb{env, callbacks};
    if (!cacheMethods(env, callbacks, cb)) {
        ret[0] = XE_ERR_FORMAT;
        return toLongArray(env, ret);
    }
    jlong* skip = skipBits != nullptr ? env->GetLongArrayElements(skipBits, nullptr) : nullptr;
    jsize skipWords = skipBits != nullptr ? env->GetArrayLength(skipBits) : 0;
    auto skipHas = [&](int64_t idx) -> bool {
        if (skip == nullptr) return false;
        size_t w = (size_t) (idx / 64);
        if (w >= (size_t) skipWords) return false;
        return (skip[w] & (1LL << (idx % 64))) != 0;
    };

    // 密码：加密 zip（ZipCrypto/AES-256）/ 加密 rar5 由 libarchive 解密；必须在 open 前注册
    const char* pass = jpassphrase != nullptr ? env->GetStringUTFChars(jpassphrase, nullptr) : nullptr;
    struct archive* a = nullptr;
    int fd = openArchive(srcFd, &a, pass);
    if (pass != nullptr) env->ReleaseStringUTFChars(jpassphrase, pass);
    if (fd >= 0) {
    struct archive_entry* entry = archive_entry_new();
    int64_t index = 0;
    int64_t bytesDone = 0, entriesDone = 0;
    int64_t inputReported = 0;
    jint finalCode = XE_OK;

        while (true) {
            int r = archive_read_next_header2(a, entry);
            if (r == ARCHIVE_EOF) break;
            if (r == ARCHIVE_FATAL) {
                setErr(archive_error_string(a) ? archive_error_string(a) : "read fatal");
                finalCode = XE_ERR_READ;
                break;
            }
            // 加密条目：无密码时结构化报错（上层弹密码框重试）
            if (archive_entry_is_encrypted(entry) || archive_entry_is_data_encrypted(entry)) {
                if (jpassphrase == nullptr) {
                    setErr("Passphrase required");
                    finalCode = XE_ERR_ENCRYPTED;
                    break;
                }
            }
            const char* rawPath = archive_entry_pathname_utf8(entry);
            if (rawPath == nullptr) rawPath = archive_entry_pathname(entry);
            const char* rawName = rawPath ? rawPath : "";
            bool isDir = archive_entry_filetype(entry) == AE_IFDIR;

            // 断点续传：skipBits 命中 → 上报 skipped（Kotlin 侧对齐 journal/计数）
            if (skipHas(index)) {
                jstring jp = env->NewStringUTF("");
                env->CallVoidMethod(cb.obj, cb.onEntrySkipped, (jlong) index, jp, (jint) 2);
                env->DeleteLocalRef(jp);
                entriesDone++;
                index++;
                continue;
            }

            char safe[4096];
            if (!sanitizePath(rawName, safe, sizeof(safe))) {
                jstring jp = env->NewStringUTF(rawName);
                env->CallVoidMethod(cb.obj, cb.onEntrySkipped, (jlong) index, jp, (jint) 1);
                env->DeleteLocalRef(jp);
                index++;
                continue;
            }

            if (env->ExceptionCheck()) { finalCode = XE_ERR_FORMAT; break; }

            jstring jname = env->NewStringUTF(safe);
            jint outFd = env->CallIntMethod(cb.obj, cb.onEntryStart,
                                            (jlong) index, jname,
                                            (jlong) archive_entry_size(entry),
                                            (jboolean) isDir);
            env->DeleteLocalRef(jname);
            if (env->ExceptionCheck()) { finalCode = XE_ERR_FORMAT; break; }

            if (outFd == -1) { // 目标已存在且策略为跳过（Kotlin 已决定）
                jstring jp = env->NewStringUTF(safe);
                env->CallVoidMethod(cb.obj, cb.onEntryDone, (jlong) index, jp, (jlong) 0);
                env->DeleteLocalRef(jp);
                entriesDone++; index++;
                continue;
            }
            if (outFd == -2) { // 目录：Kotlin 已 mkdirs
                jstring jp = env->NewStringUTF(safe);
                env->CallVoidMethod(cb.obj, cb.onEntryDone, (jlong) index, jp, (jlong) 0);
                env->DeleteLocalRef(jp);
                entriesDone++; index++;
                continue;
            }
            if (outFd < 0) { index++; continue; }

            // ---- 数据块循环（native 直写，检查点=块边界）----
            int64_t written = 0;
            bool aborted = false, entryFailed = false;
            const void* buff = nullptr;
            size_t size = 0;
            int64_t offset = 0;
            while (true) {
                r = archive_read_data_block(a, &buff, &size, &offset);
                if (r == ARCHIVE_EOF) break;
                if (r == ARCHIVE_FATAL) {
                    const char* es = archive_error_string(a);
                    LOGE("data fatal: %s", es ? es : "(null)");
                    // 密码错误/缺失（加密 zip）→ 结构化 ENCRYPTED 码供上层弹密码框
                    if (es != nullptr && (strstr(es, "Passphrase") != nullptr || strstr(es, "passphrase") != nullptr || strstr(es, "encrypted") != nullptr || strstr(es, "Encryption") != nullptr)) {
                        setErr(es);
                        finalCode = XE_ERR_ENCRYPTED;
                        entryFailed = true;
                        break;
                    }
                    setErr(es ? es : "data fatal");
                    entryFailed = true;
                    break;
                }
                if (env->CallBooleanMethod(cb.obj, cb.shouldAbort)) {
                    aborted = true;
                    break;
                }
                // 逻辑偏移（稀疏/seek 型格式）：顺序格式 offset == written
                if ((int64_t) offset > written) {
                    if (lseek(outFd, (off_t) offset, SEEK_SET) < 0) { entryFailed = true; break; }
                    written = (int64_t) offset;
                }
                const char* p = (const char*) buff;
                size_t left = size;
                while (left > 0) {
                    ssize_t n = write(outFd, p, left);
                    if (n <= 0) { setErr("write failed"); entryFailed = true; break; }
                    p += n; left -= (size_t) n; written += n;
                }
                if (entryFailed) break;
                env->CallVoidMethod(cb.obj, cb.onOutput, (jlong) size);
                int64_t inTotal = archive_filter_bytes(a, -1);
                if (inTotal > inputReported) {
                    env->CallVoidMethod(cb.obj, cb.onInput, inTotal - inputReported);
                    inputReported = inTotal;
                }
                if (env->ExceptionCheck()) { aborted = true; break; }
            }
            close(outFd);

            if (aborted) {
                jstring jp = env->NewStringUTF(safe);
                env->CallVoidMethod(cb.obj, cb.onEntryAborted, (jlong) index, jp, written);
                env->DeleteLocalRef(jp);
                finalCode = XE_ABORTED;
                break;
            }
            if (entryFailed) {
                if (finalCode == XE_ERR_ENCRYPTED) break; // 密码问题：整体退出，不按条目跳过
                if (ignoreEntryErrors) { // CRC 等条目级失败：跳过该条继续（Kotlin 侧记录 warning）
                    jstring jp = env->NewStringUTF(safe);
                    env->CallVoidMethod(cb.obj, cb.onEntrySkipped, (jlong) index, jp, (jint) 3);
                    env->DeleteLocalRef(jp);
                    index++;
                    continue;
                }
                finalCode = XE_ERR_READ;
                break;
            }

            jstring jp = env->NewStringUTF(safe);
            env->CallVoidMethod(cb.obj, cb.onEntryDone, (jlong) index, jp, written);
            env->DeleteLocalRef(jp);
            entriesDone++;
            bytesDone += written;
            index++;
        }
        archive_entry_free(entry);
        ret[0] = finalCode;
        ret[1] = entriesDone;
        ret[2] = bytesDone;
    }

    if (fd >= 0) {
        archive_read_free(a);
        close(fd);
    }
    if (skip != nullptr) env->ReleaseLongArrayElements(skipBits, skip, JNI_ABORT);
    return toLongArray(env, ret);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limao_unzip_native_XeBridge_nativeGetLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_lastError);
}

// ==================== 压缩（archive_write） ====================

namespace {

// 归档输出走自定义回调：fd 所有权归 Kotlin（detach 转移），native 只写入不关闭
struct WriteCtx { int fd; };

la_ssize_t xe_write_cb(struct archive*, void* ud, const void* buf, size_t len) {
    auto* ctx = static_cast<WriteCtx*>(ud);
    const char* p = static_cast<const char*>(buf);
    size_t done = 0;
    while (done < len) {
        ssize_t n = ::write(ctx->fd, p + done, len - done);
        if (n <= 0) return -1;
        done += (size_t) n;
    }
    return (la_ssize_t) len;
}

struct CompressCb {
    JNIEnv* env;
    jobject obj;
    jmethodID entryCount;   // ()I
    jmethodID entryName;    // (J)Ljava/lang/String;
    jmethodID entrySize;    // (J)J
    jmethodID entryMtime;   // (J)J
    jmethodID entryIsDir;   // (J)Z
    jmethodID openEntry;    // (J)I   fd 或 -1（目录）
    jmethodID entryDone;    // (JJ)V  index, bytes
    jmethodID onInput;      // (J)V
    jmethodID onOutput;     // (J)V
    jmethodID shouldAbort;  // ()Z
};

bool cacheCompressMethods(JNIEnv* env, jobject callbacks, CompressCb& cb) {
    jclass cls = env->GetObjectClass(callbacks);
    struct { const char* name; const char* sig; jmethodID* out; } methods[] = {
        {"entryCount",  "()I",                    &cb.entryCount},
        {"entryName",   "(J)Ljava/lang/String;",  &cb.entryName},
        {"entrySize",   "(J)J",                   &cb.entrySize},
        {"entryMtime",  "(J)J",                   &cb.entryMtime},
        {"entryIsDir",  "(J)Z",                   &cb.entryIsDir},
        {"openEntry",   "(J)I",                   &cb.openEntry},
        {"entryDone",   "(JJ)V",                  &cb.entryDone},
        {"onInputBytes",  "(J)V",                 &cb.onInput},
        {"onOutputBytes", "(J)V",                 &cb.onOutput},
        {"shouldAbort", "()Z",                    &cb.shouldAbort},
    };
    for (auto& m : methods) {
        *m.out = env->GetMethodID(cls, m.name, m.sig);
        if (*m.out == nullptr) { LOGE("compress GetMethodID failed: %s", m.name); return false; }
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_limao_unzip_native_XeBridge_nativeCompress(JNIEnv* env, jobject, jint outFd,
                                                    jint formatCode, jint level, jobject callbacks) {
    jlong ret[4] = {(jlong) XE_ERR_FORMAT, 0, 0, 0}; // code, entriesDone, bytesInput, bytesOutput
    CompressCb cb{env, callbacks};
    if (!cacheCompressMethods(env, callbacks, cb)) return toLongArray(env, ret);

    struct archive* w = archive_write_new();
    bool ok = true;
    switch (formatCode) {
        case 1: ok = archive_write_set_format_zip(w) == ARCHIVE_OK; break;
        case 2: ok = archive_write_set_format_7zip(w) == ARCHIVE_OK; break;
        case 3: ok = archive_write_set_format_pax(w) == ARCHIVE_OK; break;
        case 4:
            ok = archive_write_set_format_pax(w) == ARCHIVE_OK &&
                 archive_write_add_filter_gzip(w) == ARCHIVE_OK;
            break;
        default: ok = false;
    }
    if (ok && formatCode == 1) {
        char opt[40];
        snprintf(opt, sizeof(opt), "zip:compression-level=%d", level > 0 ? level : 6);
        archive_write_set_options(w, opt);
    }
    WriteCtx ctx{outFd};
    if (!ok || archive_write_open(w, &ctx, nullptr, xe_write_cb, nullptr) != ARCHIVE_OK) {
        setErr(archive_error_string(w) ? archive_error_string(w) : "archive_write_open failed");
        archive_write_free(w);
        ret[0] = XE_ERR_WRITE;
        return toLongArray(env, ret);
    }

    struct archive_entry* e = archive_entry_new();
    char buf[64 * 1024];
    jint count = env->CallIntMethod(cb.obj, cb.entryCount);
    int64_t entriesDone = 0, totalIn = 0, reportedOut = 0;
    jint finalCode = XE_OK;

    for (jint i = 0; i < count; i++) {
        if (env->CallBooleanMethod(cb.obj, cb.shouldAbort)) { finalCode = XE_ABORTED; break; }
        jstring jname = (jstring) env->CallObjectMethod(cb.obj, cb.entryName, (jlong) i);
        const char* name = env->GetStringUTFChars(jname, nullptr);
        int64_t size = env->CallLongMethod(cb.obj, cb.entrySize, (jlong) i);
        int64_t mtime = env->CallLongMethod(cb.obj, cb.entryMtime, (jlong) i);
        bool isDir = env->CallBooleanMethod(cb.obj, cb.entryIsDir, (jlong) i);

        archive_entry_clear(e);
        archive_entry_set_pathname(e, name);
        archive_entry_set_size(e, size);
        archive_entry_set_mtime(e, mtime / 1000, 0);
        archive_entry_set_filetype(e, isDir ? AE_IFDIR : AE_IFREG);
        if (archive_write_header(w, e) != ARCHIVE_OK) {
            setErr(archive_error_string(w) ? archive_error_string(w) : "write_header failed");
            env->ReleaseStringUTFChars(jname, name);
            finalCode = XE_ERR_WRITE;
            break;
        }
        env->ReleaseStringUTFChars(jname, name);
        env->DeleteLocalRef(jname);

        int64_t written = 0;
        if (!isDir) {
            int fd = env->CallIntMethod(cb.obj, cb.openEntry, (jlong) i);
            if (fd >= 0) {
                while (true) {
                    if (env->CallBooleanMethod(cb.obj, cb.shouldAbort)) { finalCode = XE_ABORTED; break; }
                    ssize_t n = ::read(fd, buf, sizeof(buf));
                    if (n < 0) { setErr("read source failed"); finalCode = XE_ERR_READ; break; }
                    if (n == 0) break;
                    la_ssize_t wn = archive_write_data(w, buf, (size_t) n);
                    if (wn < n) {
                        setErr(archive_error_string(w) ? archive_error_string(w) : "archive_write_data short");
                        finalCode = XE_ERR_WRITE;
                        break;
                    }
                    env->CallVoidMethod(cb.obj, cb.onInput, (jlong) n);
                    totalIn += n;
                    written += n;
                    int64_t outTotal = archive_filter_bytes(w, -1);
                    if (outTotal > reportedOut) {
                        env->CallVoidMethod(cb.obj, cb.onOutput, outTotal - reportedOut);
                        reportedOut = outTotal;
                    }
                }
                close(fd); // 源 fd：openEntry 已转移所有权给 native
                if (finalCode != XE_OK) break;
            }
        }
        if (finalCode != XE_OK) break;
        env->CallVoidMethod(cb.obj, cb.entryDone, (jlong) i, (jlong) written);
        entriesDone++;
    }

    archive_entry_free(e);
    archive_write_close(w);
    archive_write_free(w);
    ret[0] = finalCode;
    ret[1] = entriesDone;
    ret[2] = totalIn;
    ret[3] = reportedOut; // 归档输出总量（过滤器口径，free 之前已取）
    return toLongArray(env, ret);
}
