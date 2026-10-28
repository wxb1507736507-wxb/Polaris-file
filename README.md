# Polaris File

一款 Android 文件管理器，集文件管理与解压/压缩于一体。

## 功能

- **文件管理**：QQ浏览器式首页（存储卡、分类宫格、最近文件），按类型浏览图片 / 视频 / 音频 / 文档 / 安装包 / 下载 / 全部文件；文件夹树形浏览；多选批量操作（复制 / 移动 / 删除 / 重命名 / 分享 / 压缩）；双列与单列布局切换；全局搜索；深色阅读主题
- **解压**：ZIP、7z（LZMA/LZMA2）、TAR、TAR.GZ、BZ2、ZSTD、CPIO、AR 等（libarchive 原生引擎，ARM64）；加密 ZIP 输入密码解压；解压进度实时展示，完成后一键打开已解压目录；断点续传
- **压缩**：文件夹压缩为 ZIP / 7Z / TAR.GZ
- **文档阅读**：PDF 内建渲染翻页；Word（docx）/ Excel（xlsx）/ PPT（pptx）文本提取预览；文本编辑器支持 UTF-8 / GBK 自动识别，可直接编辑保存
- **图片查看**：内建全屏查看器，列表直接显示封面缩略图
- **WebDAV**：连接坚果云等任意 WebDAV 网盘，浏览 / 下载 / 上传 / 删除，凭证持久化自动连接
- **存储分析**：WizTree 式 treemap 色块直观展示各目录大小占比，点击下钻、直接删除
- **双语**：中文 / English 一键切换
- 权限：仅使用「所有文件访问」完成本地文件管理，不上传任何数据

## 下载

从 [Releases](https://github.com/wxb1507736507-wxb/Polaris-file/releases) 下载 APK 安装（arm64-v8a）。

## 构建

```bash
# 下载第三方 native 源码到 app/src/main/cpp/vendor/（libarchive / xz / bzip2 / zstd，见 RELEASE 说明）
./gradlew :app:assembleRelease   # 需 Android SDK + NDK
./gradlew :engine:test           # 引擎层单元测试（无 SDK 可跑）
```
