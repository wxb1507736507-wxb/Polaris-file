# Polaris File（com.limao.unzip）

Android 文件管理器：解压/压缩为核心工具能力（libarchive 原生引擎），文件管理为主体。
核心诉求：**流畅** —— 解压期间 JVM 堆增量 ≤ 32MB、缓冲复用、任意检查点可中止、进度 ≤1Hz。

## v0.7.1：交互打磨

- **长按文件取消震动**（行/格/列表容器 isHapticFeedbackEnabled=false）
- **压缩按钮等高**：固定 44dp + `isBaselineAligned=false`（真机 bounds 三按钮完全一致）
- **双列布局开关**：列表页顶栏 ▦/☰ 切换（MT 管理器式两列卡片 vs QQ浏览器单列），
  SharedPreferences 持久化，对全部七类列表（含压缩包）生效；**默认 QQ 单列**
- 修复：布局模式读取移出字段初始化器（构造期 base context 未 attach 导致 NPE 闪退）

## v0.7：编辑器 + MT 式文件操作 + 双语 + 体积优化

- **改名 Polaris File**（zh: "Polaris 文件" / en: "Polaris File"）
- **双语**：values / values-en 全量资源；「我的」页 中文/English 切换
  （AppCompatDelegate.setApplicationLocales + autoStoreLocales 持久化，重进即生效）
- **文本编辑器**（MT 风格深色主题）：txt/log/md/xml/json/csv/代码文件点击即入，
  编辑/保存（≤5MB）、行数·字符数状态栏、未保存返回询问（保存/放弃/取消）
- **文件操作（长按）**：编辑 / 重命名 / 删除（确认）/ 详情 / 复制路径——目录与文件均可
- **浏览器增强**：顶栏 🔍 搜索（名称过滤，计数徽标显示搜索词）、＋ 新建文件夹/文件
- **体积优化**：release 开启 R8 minify + shrinkResources + 资源语言限定（zh/en），
  APK **8.3MB → 3.0MB（-64%）**（debug 未混淆为开发态）
- 兼容性：MediaStore 查询全部 runCatching 包裹；滚动越界 bounds=[0,0] 时 UI 自动化已规避

## 真机自动化回归（v0.7 设备离线，待重连后执行）

待执行清单（脚本化步骤已就绪，重连即跑）：
1. release 版安装（MIUI/ColorOS 拦截页 tap 115,1476 绕过）
2. 三标签/改名验证（uiautomator text=Polaris/工具/我的）
3. 我的 → English → 标签变 Files/Tools/Me → 中文切回
4. 浏览器 ＋ 新建文件夹 zz_test → 长按重命名 zz_test2 → 删除
5. ＋ 新建文件 hello.txt → 点击进编辑器输入内容 → 保存 → 重开验证
6. 🔍 搜索过滤 → 清除
7. 压缩包一键解压/已解压询问回环回归

## v0.6：文件管理器定位 + 已解压管理

- **权限**：`MANAGE_EXTERNAL_STORAGE`（所有文件访问），启动引导页直达系统开关；
  解压/压缩走**直文件路径通道**（不再依赖 SAF 拾取器）
- **主页**（仿 QQ浏览器文件页）：手机存储卡（已用/总量/百分比）、4×2 分类宫格
  （压缩包/下载/图片/视频/音频/文档/安装包/全部文件）、最近压缩包列表、压缩入口
- **压缩包分类页**：常见目录（Download/Documents/QQ接收/BaiduNetdisk/…深度3）扫描，
  图标+文件名+大小·时间列表；点击弹操作：解压到同名目录（推荐）/当前目录
- **Worker 路径模式**：`KEY_SRC_PATH/KEY_DST_PATH` 直路径 + AndroidFileSink/AndroidFileSink fd 通道，
  通知/取消/journal 与 SAF 模式共用
- 真机验证（OPPO A58 5G/ColorOS）：UI 自动化走通
  引导授权 → 主页 → 宫格 → 列表 → 解压到 Download/limao-test/（6 文件全部落地）
- 已知限制：其余分类（图片/视频/…）为占位"即将上线"；目录浏览器未做；
  上架 Google Play 需为 MANAGE_EXTERNAL_STORAGE 提交使用声明（国内渠道常规操作）

## 压缩功能（v0.3）

```
engine/   纯 JVM 模块（无 android.* 依赖，可独立测试）
  api/        契约 v1.2：ProgressSnapshot（双计数器）、EntryCursor、ExtractRequest/Options、结果类型
  engine/     ExtractionEngine SPI + ZipReferenceEngine（纯 Kotlin 参考实现）+ FileChannelInput/FileSink
  progress/   ProgressThrottler（≤1Hz 节流，时钟可注入）
  journal/    RecoveryJournal（断点恢复日志：固定头 + 追加记录 + 容错解析）
  buffer/     ChunkBufferPool（64KB 默认，ThreadLocal 复用）
  dispatch/   EngineDispatchers.WORKERS（全局并发预算 min(核数-1, 4)）
  error/      EngineErrorCode → UserError 结构化映射（zh-CN 文案）
  extractor/  ArchiveExtractorImpl（probe→scan→journal→extract 编排）
app/       Android 应用
  MainActivity/MainViewModel   UI 状态机 + 进度渲染
  worker/ExtractionWorker      CoroutineWorker + setForeground(dataSync) + 取消桥接
  saf/DocumentTreeSink         SAF 树输出：createDocument(.part) + 裸 fd 直写 + rename 收尾
  notify/Notifications         通知栏进度 + 取消按钮
  cpp/                         native xe 引擎脚手架（JNI 桩，NDK 阶段接 libarchive/7-Zip）
```

## 构建与测试

```bash
# 引擎测试（无 Android SDK 也可跑）
./gradlew -PincludeApp=false :engine:test

# 应用构建（需 Android SDK；本机 NDK 28.2 + CMake 3.22.1）
./gradlew :app:assembleDebug
```

## 测试状态（本轮已执行，37/37 通过）

| 矩阵 ID | 测试 | 验证点 |
|---|---|---|
| B01-lite | HeapBudgetTest | 3 万条目解压堆增量 < 32MB |
| B04 | ProgressThrottlerTest | ≤1Hz（fake clock）、首尾必发、计数聚合 |
| E01 | ZipReferenceEngineTest（加密探测） | 加密标志识别 → 上层 fast-fail |
| E03 | 同上（CRC） | 翻转字节 → SKIP_CONTINUE 跳过 / ABORT 抛错 |
| E04 | 同上（截断） | EOCD 缺失 → FORMAT |
| E05 | PathGuardTest | Zip Slip（../、盘符、控制字符、超长）全拒 |
| I01 | AbortAndResumeTest | 条目间中止：已完成文件完整、无 .part |
| I02 | 同上 | 单文件块间中止：partialBytes 精确、.part 策略生效 |
| I04 | 同上 | journal 断点恢复：跳过已完成、mtime 不变、终态哨兵 |
| I05 | RecoveryJournalTest | 尾截断容错、越界停止、崩溃丢缓冲、abandon 补 flush |
| N01/N03/N04/N06 | ZipReferenceEngineTest | store/deflate/空/深路径/Unicode/GBK；input==压缩字节、output==解压字节 |

本轮修掉的四个实现缺陷（测试驱动发现）：
1. journal 头部读取漏跳 2 字节保留字段（entryCount 恒为 0）；
2. 恢复会话追加写未跟踪写偏移，从头覆盖 journal 头（定位读不推进 channel position）；
3. raw Inflater 对 0 字节 final block 需额外输入才报 finished（按 CD uncomp 判定完整）；
4. 测试用字节级 zip 构造器 CD 头多写 4 字节导致名字错位（测试侧缺陷）。

## 压缩功能（v0.3）

- engine：压缩 SPI（`CompressSource`/`FdCompressSource`/`ArchiveOutput`/`CreateFormat`）+
  `ZipReferenceCompressor`（纯 Kotlin，DEFLATED/UTF-8）+ `ArchiveCompressorImpl` 门面
  （双计数器：input=源字节 / output=归档字节；中止=条目边界+块边界；.part+rename 收尾；中止即丢弃）
- native：`archive_write` JNI（zip/7z/tar/tar.gz；归档输出走自定义写回调，fd 所有权无歧义）
- 路由：ZIP → 参考压缩器；7z/tar/tar.gz → libarchive native
- App：SAF 压缩源（DocumentsContract 递归 + fd 通道）、SAF/文件归档输出、CompressWorker、
  UI（选文件夹 + ZIP/7Z/TAR.GZ 格式组 + 开始压缩）
- 真机回环验证（OPPO A58 5G）：三格式 压缩→解压→逐文件 SHA256 比对 全部 PASS
  （8MB 源含中文名/子目录/随机二进制）
- 已知限制：压缩无断点续传（中止即丢弃 .part）；压缩 UI 链路（Worker+SAF）待真机手动走查；
  zip 走参考引擎时 `archiveBytes=-1`（无逐字节归档计数）

## NDK 阶段（已完成 v0.2）

- ✅ `app/src/main/cpp/vendor/`：libarchive 3.7.7 + xz 5.6.4（liblzma，解锁 7z LZMA/LZMA2 与 xz 过滤器）
- ✅ `xe_jni.cpp`：JNI 回调模型（批量数据不跨 JNI、native 路径守卫、中止检查点=数据块边界、
  input 计数 = archive_filter_bytes 差分）；fd 所有权规则：Kotlin `detachFd()` → native close
- ✅ 引擎路由：ZIP → 参考引擎（GBK 启发式），7z/tar/gz/cpio/ar 等 → libarchive native
- ✅ 真机验证：7z（LZMA）与 tar.gz 解压、中文名、哈希校验全部通过（OPPO A58 5G / ColorOS）
- 待办：vendor crypto（解锁加密 ZIP/7z）、zlib-ng（ARMv8 硬件 CRC）、多 worker 并行、
  RAR4/5 确认（libarchive RAR5 需 zlib ✓ 已具备）

## 已知限制（v0.7）

- 加密 ZIP(AES)/加密 7z：native 未 vendor crypto，按结构化错误上报（不支持）；
- MediaStore 分类条目（图片/视频/文档等）暂无重命名/删除（无直接路径），长按仅详情；
- 目录浏览无多选批量操作（后续版本）；工具页压缩仍用 SAF 树选择器选源目录；
- native 引擎 `Completed.bytesInput = -1`；tar.gz 类流式格式 probe 需全扫条目头（journal 需准确条目数）；
- MIUI/ColorOS 后台广播管控对调试接收器有延迟投递现象（正式链路走 WorkManager 不受影响）。

## 获取与构建

- 仓库：https://github.com/wxb1507736507-wxb/Polaris-file
- Release APK：https://github.com/wxb1507736507-wxb/Polaris-file/releases （v0.9，3.2MB）
- 第三方 native 源码不入库，构建前下载并解压到 `app/src/main/cpp/vendor/`：
  - libarchive 3.7.7 → `vendor/libarchive`（github.com/libarchive/libarchive/releases）
  - xz 5.6.4 → `vendor/xz`（github.com/tubaani-project/xz/releases，目录名 zstd 同理见下）
  - bzip2 1.0.8 → `vendor/bzip2`（sourceware.org/pub/bzip2）
  - zstd 1.5.6 → `vendor/zstd`（github.com/facebook/zstd/releases）
- `./gradlew :app:assembleRelease`（需 Android SDK/NDK 27+）；`./gradlew :engine:test` 无 SDK 可跑
