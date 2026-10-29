// unrar JNI 桥：加密 RAR（RAR3/RAR5）解压。libarchive 无 RAR 解密能力，
// 该路径由官方 unrar 源码（dll.hpp C API）承担，仅消费 options.password。
//
// fd 借用规则：与 xe_jni.cpp 相同——dup 出独立 fd，通过 /proc/self/fd/N 打开，
// 会话结束 close(dupfd)，不触碰调用方的 fd。
#include <jni.h>
#include <string>
#include <unistd.h>
#include <android/log.h>
#include "raros.hpp" // → _UNIX/_ANDROID，dll.hpp 的 HANDLE/LPARAM typedef 依赖它
#include "dll.hpp"

#define LOG_TAG "xe"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char g_unrarLastError[512] = {0};

static void setUnrarErr(const char* msg) {
    if (msg == nullptr) msg = "unknown";
    snprintf(g_unrarLastError, sizeof(g_unrarLastError), "%s", msg);
}

struct UnrarCb {
    JNIEnv* env;
    jobject obj;
    jmethodID shouldAbort;
    jmethodID onEntryStart;
    jmethodID onEntryDone;
    jmethodID onOutput;
};

static bool cacheUnrarMethods(JNIEnv* env, jobject obj, UnrarCb& cb) {
    jclass cls = env->GetObjectClass(obj);
    if (cls == nullptr) return false;
    cb.env = env; cb.obj = obj;
    cb.shouldAbort = env->GetMethodID(cls, "shouldAbort", "()Z");
    cb.onEntryStart = env->GetMethodID(cls, "onEntryStart", "(JLjava/lang/String;J)V");
    cb.onEntryDone = env->GetMethodID(cls, "onEntryDone", "(JLjava/lang/String;J)V");
    cb.onOutput = env->GetMethodID(cls, "onOutput", "(J)V");
    return cb.shouldAbort && cb.onEntryStart && cb.onEntryDone && cb.onOutput;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limao_unzip_native_XeBridge_nativeUnrarLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_unrarLastError);
}

// 返回 [code, entriesDone, bytesDone]：code=0 成功；1=中止；其余为 ERAR_* 码。
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_limao_unzip_native_XeBridge_nativeUnrarExtract(JNIEnv* env, jobject,
                                                        jint srcFd, jstring jdestDir,
                                                        jstring jpassword, jobject callbacks) {
    jlong ret[3] = {21 /*ERAR_UNKNOWN*/, 0, 0};
    UnrarCb cb{};
    if (!cacheUnrarMethods(env, callbacks, cb)) return env->NewLongArray(3);

    const char* destDir = env->GetStringUTFChars(jdestDir, nullptr);
    if (destDir == nullptr) { setUnrarErr("no destDir"); return env->NewLongArray(3); }

    char passBuf[256] = {0};
    if (jpassword != nullptr) {
        const char* p = env->GetStringUTFChars(jpassword, nullptr);
        if (p != nullptr) { snprintf(passBuf, sizeof(passBuf), "%s", p); env->ReleaseStringUTFChars(jpassword, p); }
    }

    // unrar 只接受路径：dup fd 走 /proc/self/fd
    int fd = dup(srcFd);
    if (fd < 0) { setUnrarErr("dup failed"); env->ReleaseStringUTFChars(jdestDir, destDir); return env->NewLongArray(3); }
    std::string arcPath = std::string("/proc/self/fd/") + std::to_string(fd);

    jlong entriesDone = 0, bytesDone = 0;
    jint finalCode = 0;
    bool aborted = false;

    struct RAROpenArchiveDataEx od{};
    od.ArcName = const_cast<char*>(arcPath.c_str());
    od.OpenMode = RAR_OM_EXTRACT;
    HANDLE h = RAROpenArchiveEx(&od);
    if (h == nullptr || od.OpenResult != ERAR_SUCCESS) {
        LOGE("unrar: open failed res=%d", od.OpenResult);
        setUnrarErr("unrar open failed");
        close(fd);
        env->ReleaseStringUTFChars(jdestDir, destDir);
        ret[0] = od.OpenResult != 0 ? od.OpenResult : 15 /*ERAR_EOPEN*/;
        return env->NewLongArray(3);
    }
    if (passBuf[0] != 0) RARSetPassword(h, passBuf);

    struct RARHeaderDataEx hd{};
    while (true) {
        hd.Flags = 0;
        int r = RARReadHeaderEx(h, &hd);
        if (r == ERAR_END_ARCHIVE) break;
        if (r == ERAR_MISSING_PASSWORD) {
            setUnrarErr("rar encrypted needs password");
            finalCode = 22;
            break;
        }
        if (r != ERAR_SUCCESS) {
            const char* es = ""; // dll API 无 per-error 文本，用错误码
            setUnrarErr("unrar read header");
            finalCode = r;
            LOGE("unrar: header err=%d", r);
            break;
        }
        if (cb.env->CallBooleanMethod(cb.obj, cb.shouldAbort)) { aborted = true; break; }

        std::string name(hd.FileName);
        jlong expect = ((jlong) hd.UnpSizeHigh << 32) | (jlong) hd.UnpSize;
        jstring jname = cb.env->NewStringUTF(name.c_str());
        cb.env->CallVoidMethod(cb.obj, cb.onEntryStart, (jlong) entriesDone, jname, expect);
        cb.env->DeleteLocalRef(jname);
        if (cb.env->ExceptionCheck()) { aborted = true; break; }

        int pr = RARProcessFile(h, RAR_EXTRACT, const_cast<char*>(destDir), nullptr);
        if (pr != ERAR_SUCCESS) {
            finalCode = pr;
            if (pr == ERAR_BAD_PASSWORD) setUnrarErr("rar bad password");
            else if (pr == ERAR_MISSING_PASSWORD) setUnrarErr("rar encrypted needs password");
            else setUnrarErr("unrar process failed");
            LOGE("unrar: process err=%d entry=%s", pr, name.c_str());
            break;
        }
        entriesDone++;
        bytesDone += expect;
        jstring jn = cb.env->NewStringUTF(name.c_str());
        cb.env->CallVoidMethod(cb.obj, cb.onEntryDone, (jlong) (entriesDone - 1), jn, expect);
        cb.env->CallVoidMethod(cb.obj, cb.onOutput, expect);
        cb.env->DeleteLocalRef(jn);
        if (cb.env->ExceptionCheck()) { aborted = true; break; }
    }
    RARCloseArchive(h);
    close(fd);
    env->ReleaseStringUTFChars(jdestDir, destDir);

    if (aborted) finalCode = 1; // XE_ABORTED 约定
    ret[0] = finalCode;
    ret[1] = entriesDone;
    ret[2] = bytesDone;
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, ret);
    return out;
}
