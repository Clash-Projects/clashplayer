cmake_minimum_required(VERSION 3.22.1)
project(usbiso C)

add_library(usbiso SHARED usbiso.c)
target_compile_options(usbiso PRIVATE -Wall -Wextra -Wno-unused-parameter -Werror -O2)
