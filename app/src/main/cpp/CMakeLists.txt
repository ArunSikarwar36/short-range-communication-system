cmake_minimum_required(VERSION 3.22.1)
project(paai_opus C)

# libopus 1.5.2 (BSD-3-Clause, see opus/COPYING), vendored from
# https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
# SHA-256 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
# The neural-network extensions (DRED, OSCE, deep PLC) are left disabled.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC OFF CACHE BOOL "" FORCE)
add_subdirectory(opus EXCLUDE_FROM_ALL)

add_library(paai_opus SHARED opus_jni.c)
target_link_libraries(paai_opus PRIVATE opus log)
# Android 15+ devices may use 16 KB memory pages.
target_link_options(paai_opus PRIVATE "-Wl,-z,max-page-size=16384")
