cmake_minimum_required(VERSION 3.22.1)
project("kisara_native")

add_library(kisara_native SHARED native-lib.c)
find_library(log-lib log)
target_link_libraries(kisara_native ${log-lib})
