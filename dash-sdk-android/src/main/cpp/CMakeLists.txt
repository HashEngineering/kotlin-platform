cmake_minimum_required(VERSION 3.10)
SET(CMAKE_CXX_STANDARD 17)
SET(CMAKE_CXX_STANDARD_REQUIRED ON)
SET(BUILD_MODE Release)
SET(CMAKE_CXX_FLAGS "-Wall -fPIC")
SET(CMAKE_CXX_FLAGS_DEBUG "-g")
SET(CMAKE_CXX_FLAGS_RELEASE "-O3")
SET(ANDROID_API_LEVEL 24)
project(sdk)

message(STATUS "ANDROID_ABI=${ANDROID_ABI}")
# get the --target triple for the static rust library
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RUST_TARGET_TRIPLE "armv7-linux-androideabi")
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RUST_TARGET_TRIPLE "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86")
    set(RUST_TARGET_TRIPLE "i686-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(RUST_TARGET_TRIPLE "x86_64-linux-android")
endif()

# Define the C source file
set(C_SOURCE_FILE ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/build/generated/cpp/sdk.cpp
        ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp/java_map_keyid_identitypublickey.cpp
        ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp/java_list_value.cpp
        ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp/java_map_string_value.cpp
        ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp/java_map_valuemap.cpp
        ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp/context_provider.cpp
        ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp/java_vm.cpp
        ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp/wrapper.h
)

# Define the Rust libraryn config
set(RUST_LIB_NAME "dash_sdk_bindings")
set(RUST_LIB_FILE "lib${RUST_LIB_NAME}.a")
set(RUST_SOURCE_DIR "${CMAKE_SOURCE_DIR}/../rust")
# Cargo may redirect its target directory (CARGO_TARGET_DIR, or build.target-dir
# in ~/.cargo/config.toml), so ask cargo where it writes instead of assuming
# ../rust/target. Otherwise we link a stale local copy of the static library.
# Override with -DCARGO_TARGET_DIR=/path if needed.
if(NOT DEFINED CARGO_TARGET_DIR)
    execute_process(
        COMMAND cargo +nightly metadata --format-version 1 --no-deps
        WORKING_DIRECTORY ${RUST_SOURCE_DIR}
        OUTPUT_VARIABLE CARGO_METADATA
        RESULT_VARIABLE CARGO_METADATA_RESULT
        ERROR_QUIET)
    if(CARGO_METADATA_RESULT EQUAL 0 AND CARGO_METADATA MATCHES "\"target_directory\":\"([^\"]+)\"")
        set(CARGO_TARGET_DIR "${CMAKE_MATCH_1}")
    else()
        message(WARNING "cargo metadata failed; assuming ${RUST_SOURCE_DIR}/target")
        set(CARGO_TARGET_DIR "${RUST_SOURCE_DIR}/target")
    endif()
endif()
set(RUST_LIB_OUTPUT_PATH "${CARGO_TARGET_DIR}/${RUST_TARGET_TRIPLE}/release/${RUST_LIB_FILE}")
message(STATUS "Rust library: ${RUST_LIB_OUTPUT_PATH}")
file(
    GLOB_RECURSE
    RUST_PROJECT_FILES "${RUST_SOURCE_DIR}/build.rs"
                       "${RUST_SOURCE_DIR}/src/*.rs"
                       "${RUST_SOURCE_DIR}/Cargo.toml"
)
# Set RUSTFLAGS for 16KB page alignment
set(RUSTFLAGS_16KB "-C link-arg=-Wl,-z,max-page-size=16384")

# Command to build the Rust Bindings Library
add_custom_command(
    OUTPUT ${RUST_LIB_OUTPUT_PATH}
    COMMAND ${CMAKE_COMMAND} -E touch ${RUST_SOURCE_DIR}/Cargo.toml
    COMMAND ${CMAKE_COMMAND} -E env RUSTFLAGS=${RUSTFLAGS_16KB} cargo +nightly ndk -t ${RUST_TARGET_TRIPLE} --platform ${ANDROID_API_LEVEL} build --release -Zbuild-std
    WORKING_DIRECTORY ${RUST_SOURCE_DIR}
    COMMENT "Building Rust library with cargo ndk"
    DEPENDS ${RUST_PROJECT_FILES}
    VERBATIM
)
add_custom_target(BuildRustLib ALL
    DEPENDS ${RUST_LIB_OUTPUT_PATH}
)
set_target_properties(BuildRustLib PROPERTIES
    LOCATION ${RUST_LIB_OUTPUT_PATH}
)

# Java
set(JAVA_AWT_LIBRARY NotNeeded)
set(JAVA_JVM_LIBRARY NotNeeded)
set(JAVA_INCLUDE_PATH2 NotNeeded)
set(JAVA_AWT_INCLUDE_PATH NotNeeded)
find_package(JNI REQUIRED)
# required for android log
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

# Create the shared library that links C++ and Rust code
include_directories(${JNI_INCLUDE_DIRS}, ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-java/src/main/cpp)
add_library(sdklib SHARED ${C_SOURCE_FILE})
add_dependencies(sdklib BuildRustLib)
target_link_libraries(sdklib PRIVATE ${RUST_LIB_OUTPUT_PATH} ${LOG_LIB} ${ANDROID_LIB})