# Polaris File（北极星文件）

**一款集文件管理、压缩解压、文档阅读、云盘协作于一体的 Android 一站式文件管理工具。**

[![Release](https://img.shields.io/github/v/release/wxb1507736507-wxb/Polaris-file)](https://github.com/wxb1507736507-wxb/Polaris-file/releases)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green)](#系统要求)
[![Language](https://img.shields.io/badge/language-中文%20%2F%20English-blue)](#个性化设置)

---

## 产品概述

Polaris File 是一款面向移动办公与日常文件管理场景的 Android 应用，以「本地优先、隐私安全」为设计原则，提供从文件浏览、分类检索、批量操作，到压缩包解压与打包、多格式文档阅读编辑、存储空间分析、WebDAV 云盘协作的完整能力闭环。应用采用原生引擎与自研双引擎架构，在保证格式覆盖广度的同时，对内存占用、进度反馈与异常恢复进行了系统性工程约束。

全部功能仅在本地执行，不采集、不上传任何用户数据。

---

## 核心功能

### 一、文件管理

- **分类首页**：QQ 浏览器式布局，集成存储卡容量视图、11 类内容宫格（压缩包、网盘下载、图片、视频、音频、文档、安装包、全部文件、存储分析、最近、WebDAV）与最近文件动态列表，各类目实时计数。
- **多模式浏览**：文件夹树形浏览、双列 / 单列布局一键切换、全局关键字搜索。
- **批量操作**：长按进入多选模式，支持批量复制、移动、删除、重命名、分享与压缩；操作过程实时反馈，结果可追溯。
- **最近文件**：基于 MediaStore 全类型检索，按修改时间倒序聚合展示，图片与视频直接呈现封面缩略图。

### 二、压缩包解压

| 能力 | 说明 |
| --- | --- |
| 格式覆盖 | ZIP、7z（LZMA / LZMA2）、TAR、TAR.GZ、BZ2、ZSTD、CPIO、AR、RAR（含 RAR4 / RAR5） |
| 加密支持 | 传统加密 ZIP（ZipCrypto）密码解压；加密 RAR3 / RAR5 官方引擎密码解密 |
| 解压体验 | 点击即解压，后台服务承载，实时进度（输入 / 输出双计数），完成后一键打开目标目录 |
| 断点续传 | 解压中断后可从检查点恢复，已完成条目自动跳过 |
| 安全防护 | 内建 PathGuard 路径净化，防御 Zip Slip 路径穿越攻击 |

### 三、压缩打包

- 支持将文件与文件夹打包为 **ZIP / 7Z / TAR.GZ** 三种格式，多选批量压缩一键完成。
- ZIP 采用自研参考压缩引擎（支持 UTF-8 与 GBK 文件名场景），7Z / TAR.GZ 由原生引擎承载。

### 四、文档阅读与编辑

- **PDF**：内建渲染引擎，逐页加载、流畅翻阅，无需第三方应用。
- **Office 文档**：Word（docx）/ Excel（xlsx）/ PPT（pptx）文本提取预览，支持中英文内容。
- **万能文本编辑器**：UTF-8 / GBK 编码自动识别，≤5MB 文件直接编辑；保存采用**原子写入**（临时文件 + 重命名），杜绝写一半损坏原文件；5–30MB 只读预览；返回前自动提示保存。

### 五、图片与媒体

- 内建全屏图片查看器，按屏宽采样解码，大图不占用过量内存。
- 全局缩略图体系：列表页、多选模式、最近文件中的图片与视频均展示封面。
- 音视频调用系统播放器打开，格式支持跟随系统。

### 六、存储空间分析

- WizTree 式矩形树图（Treemap）直观呈现各目录容量占比，点击逐级下钻，支持直接清理。

### 七、WebDAV 云盘

- 兼容任意标准 WebDAV 服务（坚果云、群晖、Nextcloud 等），支持浏览、下载、上传、删除全操作。
- 账号凭据本机加密留存，再次进入自动连接，免重复登录。

### 八、个性化设置

- **多语言**：中文 / English 应用内一键切换，跟随系统语言自动匹配。
- **外观模式**：日间模式 / 夜间模式 / 跟随系统三档可选；跟随系统模式下，系统深浅色切换即时生效，全界面完整适配深色调色板。

---

## 技术架构

### 解压引擎矩阵

| 引擎 | 承载格式 | 特性 |
| --- | --- | --- |
| Polaris 参考引擎（纯 Kotlin） | ZIP（Store / Deflate / Zip64） | GBK 文件名启发式识别、块级中止、行为基线 |
| libarchive 3.7.7（原生） | 7z / TAR / TAR.GZ / BZ2 / ZSTD / CPIO / AR / 明文 RAR | fd 直通零拷贝通道、统一进度回调 |
| 官方 UnRAR 7.1.8（原生） | 加密 RAR3 / RAR5 | 参考级解密实现 |

### 工程约束

- **内存红线**：解压全程 JVM 堆增量 ≤ 32MB，固定 64KB 缓冲池复用，条目游标零分配复用。
- **进度纪律**：双计数器（输入 / 输出）+ 1Hz 节流上报，界面零卡顿。
- **恢复能力**：位图检查点日志（RecoveryJournal），200ms / 512 条目落盘。
- **并发预算**：全局工作线程预算（min(核数−1, 4)），多任务互不挤占。
- **体积优化**：R8 混淆 + 资源收缩，APK 约 7MB（arm64-v8a + x86_64 双架构）。

---

## 质量保障

- **21 项端到端自动化用例**（真机 / 模拟器 instrumented）：Office 提取、文本编辑保存（UTF-8 / GBK / 只读）、三格式压缩解压回环、样例包解压、PDF 渲染、图片解码，内容逐字节断言。
- **7 项 RAR 专项用例**：RAR4 / RAR5 明文与加密、错误密码结构化报错、rarlab 官方真实样例。
- **WebDAV 真实服务端走查**：PROPFIND / GET / PUT / DELETE 四操作全链路验证。
- **全界面人工走查**：解压、编辑、压缩、六大查看器、存储分析、主题与语言切换，逐屏截图核对。

---

## 安全与隐私

- 应用仅申请「所有文件访问」一项敏感权限，用于本地文件管理。
- 全部数据处理与解压操作在设备本地完成，无任何网络数据采集行为；WebDAV 为用户主动配置的自有云盘直连。
- 文件写入统一采用临时文件 + 原子重命名策略，异常断电不损坏既有数据。

---

## 系统要求

| 项目 | 要求 |
| --- | --- |
| 系统版本 | Android 8.0（API 26）及以上 |
| CPU 架构 | arm64-v8a（主）/ x86_64（模拟器） |
| 权限 | 所有文件访问（MANAGE_EXTERNAL_STORAGE）、通知（解压进度） |

## 下载安装

前往 [Releases](https://github.com/wxb1507736507-wxb/Polaris-file/releases) 页面下载最新 APK（如 `PolarisFile-v0.9.15-release.apk`），安装后按引导授予文件访问权限即可使用。

## 构建指南

```bash
# 1. 准备第三方 native 源码，置于 app/src/main/cpp/vendor/
#    （libarchive / xz / bzip2 / zstd / unrar）
# 2. 构建
./gradlew :app:assembleRelease      # 需 Android SDK + NDK
./gradlew :engine:test              # 引擎层 JVM 单元测试（无 SDK 可运行）
./gradlew :app:connectedDebugAndroidTest   # 端到端 instrumented 测试（需设备）
```

## 版本沿革

| 版本 | 主要更新 |
| --- | --- |
| v0.9.15 | 全功能 E2E 测试轮次；文本编辑器原子保存加固；21 项自动化用例 |
| v0.9.14 | 夜间模式（日间 / 夜间 / 跟随系统），全界面深色适配 |
| v0.9.13 | 加密 RAR 支持（官方 UnRAR 引擎）；加密 ZIP 密码注册修复 |
| v0.9.12 | RAR4 / RAR5 解压支持 |
| v0.9.11 | 全局路径化缩略图体系 |
| v0.9.10 | WebDAV 网盘（自动连接、上传 / 下载 / 删除） |
| v0.9.7–9 | Office 预览、APK 分类、加载提速、安装包模块完善 |
| v0.9.1–6 | 文件管理主体、解压引擎、压缩、存储分析、文本编辑器 |

## 第三方组件与许可
| MaterialFiles（部分元数据工具类抄录适配） | 播放器音频元数据加载 | **GPL-3.0**（引入其源码的文件遵循 GPL-3.0） |

| 组件 | 用途 | 许可 |
| --- | --- | --- |
| libarchive 3.7.7 | 多格式归档读写 | BSD |
| UnRAR 7.1.8 源码 | 加密 RAR 解压 | UnRAR 许可（仅限解压用途，不得用于重建 RAR 压缩算法） |
| xz (liblzma) 5.6.4 | LZMA / LZMA2 解码 | 公有领域 / LGPL-2.1+ |
| bzip2 1.0.8 | BZip2 解码 | BSD 风格 |
| zstd 1.5.6 | ZSTD 解码 | BSD / GPL 双许可（采用 BSD 部分） |
| OkHttp 4.12 | WebDAV 网络层 | Apache-2.0 |
| AndroidX / Material Components | UI 框架 | Apache-2.0 |

## 开源许可

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，完整条款见 [LICENSE](LICENSE)。

Copyright (C) 2026 wxb1507736507-wxb

你可以自由使用、修改和分发本项目，但衍生作品须以相同许可开源；本项目不附带任何担保。

---

## English Overview

Polaris File is an all-in-one file manager for Android featuring category-based browsing, multi-select batch operations, archive extraction (ZIP / 7z / TAR / TAR.GZ / BZ2 / ZSTD / CPIO / AR / RAR4 / RAR5, including encrypted archives), archive creation (ZIP / 7Z / TAR.GZ), built-in PDF rendering, Office (docx / xlsx / pptx) text preview, a UTF-8 / GBK text editor with atomic save, WizTree-style storage analysis, and full WebDAV cloud-drive support. It ships with a light / dark / follow-system theme and bilingual (Chinese / English) UI. All processing is performed locally — no user data is collected or uploaded.

Licensed under the GNU General Public License v3.0 — see [LICENSE](LICENSE).

---

**Polaris File · 让移动文件管理更专业。**
