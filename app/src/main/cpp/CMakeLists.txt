cmake_minimum_required(VERSION 3.22.1)
project(metronome LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(metronome SHARED ClickEngine.cpp jni.cpp)
target_compile_options(metronome PRIVATE -Wall -Wextra -O2)
target_link_libraries(metronome oboe::oboe log)
