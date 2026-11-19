cmake_minimum_required(VERSION 3.22.1)
project(usbiso C)

add_library(usbiso SHARED usbiso.c)
target_compile_options(usbiso PRIVATE -Wall -Wextra -Wno-unused-parameter -Werror -O2)

add_library(nativeout SHARED nativeout.c)
target_compile_options(nativeout PRIVATE -Wall -Wextra -Wno-unused-parameter -Werror -O2)
# Android 28+ AAudio calls are weakly linked and guarded by __builtin_available
target_compile_definitions(nativeout PRIVATE __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__)
target_compile_options(nativeout PRIVATE -Werror=unguarded-availability)
target_link_libraries(nativeout aaudio OpenSLES)
