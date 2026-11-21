cmake_minimum_required(VERSION 3.22.1)
project(reu_native VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(reu_core_cpp STATIC
    src/elf_parser.cpp
    src/metadata_parser.cpp
    src/unityfs_parser.cpp
)

target_include_directories(reu_core_cpp PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_compile_options(reu_core_cpp PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
)

if(ANDROID)
    find_library(log-lib log)
    add_library(reu_native SHARED
        jni/reu_jni.cpp
    )
    target_link_libraries(reu_native PRIVATE
        reu_core_cpp
        ${log-lib}
    )
else()
    enable_testing()
    add_executable(reu_native_tests
        tests/native_unit_tests.cpp
    )
    target_link_libraries(reu_native_tests PRIVATE reu_core_cpp)
    add_test(NAME reu_native_unit_tests COMMAND reu_native_tests)
endif()
