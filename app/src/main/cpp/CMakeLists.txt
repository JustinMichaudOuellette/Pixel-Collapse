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

# Auto-vectorisation is already on at -O3, but asking for it explicitly costs nothing and makes the
# intent reviewable. What it does here was checked rather than assumed: see the note below.
#
# Asking what the vectoriser actually does needs clang invoked directly, because AGP does not surface
# the remarks: compile app/src/main/cpp/wfc.c with the NDK clang and the same flags, plus
#   -Rpass=loop-vectorize -Rpass=slp-vectorize -Rpass-analysis=loop-vectorize
# and without -flto=thin (which defers the back end to link time). On this code it vectorises the
# tile-set build and nothing on the search path: the revision loop walks set bits one at a time
# (`bit = ctz(bits); bits &= bits - 1`) with a memory index derived from that bit, so clang reports
# "cannot identify array bounds" / "could not determine number of loop iterations" for every hot loop.
# Enabling the flags changed no measured solve time; see the note in wfc.c next to revise().

target_compile_options(wfc PRIVATE
    $<$<NOT:$<CONFIG:Debug>>:-O3>
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
    $<$<NOT:$<CONFIG:Debug>>:-fno-math-errno>
    $<$<NOT:$<CONFIG:Debug>>:-fno-trapping-math>
    $<$<NOT:$<CONFIG:Debug>>:-ffp-contract=fast>
    $<$<NOT:$<CONFIG:Debug>>:-funroll-loops>
    $<$<NOT:$<CONFIG:Debug>>:-fomit-frame-pointer>
    $<$<NOT:$<CONFIG:Debug>>:-fvectorize>
    $<$<NOT:$<CONFIG:Debug>>:-fslp-vectorize>
    -fvisibility=hidden
    -Wall
    -Wextra)

target_link_options(wfc PRIVATE
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
    $<$<NOT:$<CONFIG:Debug>>:-fuse-ld=lld>
    $<$<NOT:$<CONFIG:Debug>>:-Wl,--gc-sections>
    $<$<NOT:$<CONFIG:Debug>>:-Wl,-O2>
    # 16 KB page size support: align every PT_LOAD segment of libwfc.so to 16 KB so the library can be
    # mapped on a device with 16 KB pages (Android 15+), not only on 4 KB ones. Without it the segments
    # come out at 0x1000 and the app is rejected on those devices. NDK r28 makes this the default;
    # asking explicitly keeps r27 compliant and makes the requirement visible in the build.
    -Wl,-z,max-page-size=16384)

# pow() from libm for the weighted tile order, and nothing else.
target_link_libraries(wfc m)
