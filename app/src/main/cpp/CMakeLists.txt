cmake_minimum_required(VERSION 3.22)

project(wfc C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# The solver is the only hot code in the app: one collapse of a 76x135 grid performs millions of
# bitset operations, so the release paths get the full treatment. Debug builds keep the NDK's
# RelWithDebInfo defaults, which are still optimised enough to be usable interactively.
#
# LTO is spelled out rather than turned on with INTERPROCEDURAL_OPTIMIZATION because CMake 3.22's
# Clang support hardcodes `-fuse-ld=gold` for it (Modules/Compiler/Clang.cmake), and NDK r27 no longer
# ships gold -- linking then fails with "invalid linker name". lld is the linker it wants.
add_library(wfc SHARED
    wfc.c
    wfc_jni.c)

target_compile_options(wfc PRIVATE
    $<$<NOT:$<CONFIG:Debug>>:-O3>
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
    $<$<NOT:$<CONFIG:Debug>>:-fno-math-errno>
    $<$<NOT:$<CONFIG:Debug>>:-funroll-loops>
    $<$<NOT:$<CONFIG:Debug>>:-fomit-frame-pointer>
    -fvisibility=hidden
    -Wall
    -Wextra)

target_link_options(wfc PRIVATE
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
    $<$<NOT:$<CONFIG:Debug>>:-fuse-ld=lld>
    $<$<NOT:$<CONFIG:Debug>>:-Wl,--gc-sections>
    $<$<NOT:$<CONFIG:Debug>>:-Wl,-O2>)

# pow() from libm for the weighted tile order, and nothing else.
target_link_libraries(wfc m)
