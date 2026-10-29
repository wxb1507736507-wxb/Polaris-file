# 礼帽解压 native 引擎（xe）：libarchive 静态子项目 + JNI 实现
#
# 精简配置：仅 zlib（NDK 自带），无 crypto/iconv/lzma/zstd 等外部依赖
#   → 支持：ZIP(store/deflate/zip64)、7z(LZMA/LZMA2/PPMd，明文)、TAR/GZ/BZ2?(no bz2)、CPIO/AR/ISO 等
#   → 不支持：加密 ZIP(AES)/加密 7z —— 待 vendor crypto 后开启（结构化错误上报）
# 路由策略：ZIP 走 Kotlin 参考引擎（GBK 名启发式），其余格式走本引擎。

cmake_minimum_required(VERSION 3.22.1)
project(xe LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- libarchive 精简配置 ----
set(ENABLE_WERROR OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_TEST OFF CACHE BOOL "" FORCE)
set(ENABLE_COMMONCRYPTO OFF CACHE BOOL "" FORCE)
set(ENABLE_CNG OFF CACHE BOOL "" FORCE)
set(ENABLE_MBEDTLS OFF CACHE BOOL "" FORCE)
set(ENABLE_NETTLE OFF CACHE BOOL "" FORCE)
set(ENABLE_OPENSSL OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBB2 OFF CACHE BOOL "" FORCE)
set(ENABLE_ICONV OFF CACHE BOOL "" FORCE)
set(ENABLE_LIBXML2 OFF CACHE BOOL "" FORCE)
set(ENABLE_EXPAT OFF CACHE BOOL "" FORCE)
set(ENABLE_PCREPOSIX OFF CACHE BOOL "" FORCE)
set(ENABLE_PCRE2POSIX OFF CACHE BOOL "" FORCE)
set(ENABLE_LZO OFF CACHE BOOL "" FORCE)
set(ENABLE_LZ4 OFF CACHE BOOL "" FORCE)
set(ENABLE_ZSTD OFF CACHE BOOL "" FORCE)
set(ENABLE_BZip2 OFF CACHE BOOL "" FORCE)
set(ENABLE_CAT OFF CACHE BOOL "" FORCE)
set(ENABLE_CPIO OFF CACHE BOOL "" FORCE)
set(ENABLE_TAR OFF CACHE BOOL "" FORCE)
set(ENABLE_UNZIP OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ZLIB_ROOT "${CMAKE_ANDROID_NDK}/sources" CACHE PATH "" FORCE)  # NDK zlib 由工具链提供

# ---- xz/liblzma + bzip2 + zstd（libarchive 过滤器：7z LZMA / xz / bz2 / zstd） ----
set(XZ_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/xz)
set(BZ2_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/bzip2)
set(ZSTD_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vendor/zstd)
set(ENABLE_LZMA ON CACHE BOOL "" FORCE)
set(ENABLE_BZip2 ON CACHE BOOL "" FORCE)
set(ENABLE_ZSTD ON CACHE BOOL "" FORCE)
set(ENABLE_SMALL OFF CACHE BOOL "" FORCE)
set(LZIP_DECODER OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/xz EXCLUDE_FROM_ALL)
add_subdirectory(vendor/bzip2 EXCLUDE_FROM_ALL)
add_subdirectory(vendor/zstd/build/cmake EXCLUDE_FROM_ALL)

add_subdirectory(vendor/libarchive EXCLUDE_FROM_ALL)

# ---- unrar 7.1.8（官方参考引擎：加密 RAR3/RAR5 解密，libarchive 无此能力） ----
set(UNRAR_DIR vendor/unrar)
set(UNRAR_SOURCES
    ${UNRAR_DIR}/rar.cpp
    ${UNRAR_DIR}/strlist.cpp
    ${UNRAR_DIR}/strfn.cpp
    ${UNRAR_DIR}/pathfn.cpp
    ${UNRAR_DIR}/smallfn.cpp
    ${UNRAR_DIR}/global.cpp
    ${UNRAR_DIR}/file.cpp
    ${UNRAR_DIR}/filefn.cpp
    ${UNRAR_DIR}/filcreat.cpp
    ${UNRAR_DIR}/archive.cpp
    ${UNRAR_DIR}/arcread.cpp
    ${UNRAR_DIR}/unicode.cpp
    ${UNRAR_DIR}/system.cpp
    ${UNRAR_DIR}/crypt.cpp
    ${UNRAR_DIR}/crc.cpp
    ${UNRAR_DIR}/rawread.cpp
    ${UNRAR_DIR}/encname.cpp
    ${UNRAR_DIR}/resource.cpp
    ${UNRAR_DIR}/match.cpp
    ${UNRAR_DIR}/timefn.cpp
    ${UNRAR_DIR}/rdwrfn.cpp
    ${UNRAR_DIR}/consio.cpp
    ${UNRAR_DIR}/options.cpp
    ${UNRAR_DIR}/errhnd.cpp
    ${UNRAR_DIR}/rarvm.cpp
    ${UNRAR_DIR}/secpassword.cpp
    ${UNRAR_DIR}/rijndael.cpp
    ${UNRAR_DIR}/getbits.cpp
    ${UNRAR_DIR}/sha1.cpp
    ${UNRAR_DIR}/sha256.cpp
    ${UNRAR_DIR}/blake2s.cpp
    ${UNRAR_DIR}/hash.cpp
    ${UNRAR_DIR}/extinfo.cpp
    ${UNRAR_DIR}/extract.cpp
    ${UNRAR_DIR}/volume.cpp
    ${UNRAR_DIR}/list.cpp
    ${UNRAR_DIR}/find.cpp
    ${UNRAR_DIR}/unpack.cpp
    ${UNRAR_DIR}/headers.cpp
    ${UNRAR_DIR}/threadpool.cpp
    ${UNRAR_DIR}/rs16.cpp
    ${UNRAR_DIR}/cmddata.cpp
    ${UNRAR_DIR}/ui.cpp
    ${UNRAR_DIR}/largepage.cpp
    ${UNRAR_DIR}/filestr.cpp
    ${UNRAR_DIR}/scantree.cpp
    ${UNRAR_DIR}/dll.cpp
    ${UNRAR_DIR}/qopen.cpp
)
add_library(unrar_static STATIC ${UNRAR_SOURCES})
target_include_directories(unrar_static PRIVATE ${UNRAR_DIR})
target_compile_definitions(unrar_static PRIVATE
    RARDLL SILENT
    _FILE_OFFSET_BITS=64 _LARGEFILE_SOURCE RAR_SMP)
target_compile_options(unrar_static PRIVATE
    -std=c++11
    -Wno-logical-op-parentheses -Wno-switch -Wno-dangling-else
    -Wno-deprecated-declarations -Wno-unused-variable -Wno-unused-parameter)

add_library(xe SHARED xe_jni.cpp xe_unrar_jni.cpp)
target_include_directories(xe PRIVATE ${UNRAR_DIR})

find_library(z-lib z)
target_link_libraries(xe
    archive_static
    liblzma
    bz2
    libzstd_static
    unrar_static
    ${z-lib}
    log)

# 抑制 libarchive 子项目的告警噪声
target_compile_options(archive_static PRIVATE -w)
