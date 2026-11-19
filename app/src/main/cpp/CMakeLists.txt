cmake_minimum_required(VERSION 3.22.1)
project(clashplayer_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Support 16 KB page sizes on Android 15+ (e.g. Galaxy S25 / Pixel 9+)
add_link_options("-Wl,-z,max-page-size=16384")

add_library(usbiso SHARED usbiso.c)
target_compile_options(usbiso PRIVATE -Wall -Wextra -Wno-unused-parameter -Werror -O2)

add_library(
    nativeout
    SHARED
    nativeout.c
    DspProcessor.cpp
    ParametricEq.cpp
    ClarityBridge.cpp
)
target_compile_options(nativeout PRIVATE -Wall -Wextra -Wno-unused-parameter -O2)
# Android 28+ AAudio calls are weakly linked and guarded by __builtin_available
target_compile_definitions(nativeout PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_compile_options(nativeout PRIVATE -Werror=unguarded-availability)
target_link_libraries(nativeout aaudio OpenSLES log)
