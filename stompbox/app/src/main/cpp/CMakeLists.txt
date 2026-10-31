cmake_minimum_required(VERSION 3.22.1)
project(stompbox LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe comes from the com.google.oboe:oboe AAR via prefab.
find_package(oboe REQUIRED CONFIG)

add_library(stompbox SHARED stomp_engine.cpp)
# The audio callback runs every ~2 ms, so optimise even in debug builds.
target_compile_options(stompbox PRIVATE -O2 -Wall -Wextra)
target_link_libraries(stompbox oboe::oboe log)
