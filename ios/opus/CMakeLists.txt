cmake_minimum_required(VERSION 3.22)
project(srcs_opus C)

# Same libopus 1.5.2 sources and options as the Android build (app/src/main/cpp).
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp/opus opus EXCLUDE_FROM_ALL)

# Swift cannot call opus_encoder_ctl (a variadic C function), so the app uses this small wrapper.
add_library(srcs_opus STATIC srcs_opus.c)
target_link_libraries(srcs_opus PRIVATE opus)
