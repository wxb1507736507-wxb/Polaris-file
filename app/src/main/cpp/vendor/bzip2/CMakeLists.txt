# bzip2 静态库（libarchive BZip2 过滤器依赖）
cmake_minimum_required(VERSION 3.22.1)
project(bz2 LANGUAGES C)

add_library(bz2 STATIC
    blocksort.c
    huffman.c
    crctable.c
    randtable.c
    compress.c
    decompress.c
    bzlib.c)

target_include_directories(bz2 PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(bz2 PRIVATE -w)
