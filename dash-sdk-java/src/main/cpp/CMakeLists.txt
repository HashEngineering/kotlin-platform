cmake_minimum_required(VERSION 3.19)
SET(CMAKE_CXX_STANDARD 17)
SET(CMAKE_CXX_STANDARD_REQUIRED ON)
SET(BUILD_MODE Debug)
SET(CMAKE_CXX_FLAGS "-Wall -fPIC")
SET(CMAKE_CXX_FLAGS_DEBUG "-g")
SET(CMAKE_CXX_FLAGS_RELEASE "-O3")
project(sdk)

# Define the C source file
set(C_SOURCE_FILE ${CMAKE_SOURCE_DIR}/../../../build/generated/cpp/sdk.cpp
        java_map_keyid_identitypublickey.cpp
        java_list_value.cpp
        java_map_string_value.cpp
        java_map_valuemap.cpp
        context_provider.cpp
        java_vm.cpp
        wrapper.h)

# Locate the pre-built Rust static library. Cargo may redirect its target
# directory (CARGO_TARGET_DIR, or build.target-dir in ~/.cargo/config.toml),
# so ask cargo where it is instead of assuming dash-sdk-bindings/target.
# Override with -DRUST_LIBRARY=/path/to/libdash_sdk_bindings.a if needed.
set(RUST_CRATE_DIR ${CMAKE_SOURCE_DIR}/../../../../dash-sdk-bindings)
if(NOT DEFINED RUST_LIBRARY)
    execute_process(
        COMMAND cargo metadata --format-version 1 --no-deps
        WORKING_DIRECTORY ${RUST_CRATE_DIR}
        OUTPUT_VARIABLE CARGO_METADATA
        RESULT_VARIABLE CARGO_METADATA_RESULT
        ERROR_QUIET)
    if(CARGO_METADATA_RESULT EQUAL 0)
        string(JSON CARGO_TARGET_DIR GET "${CARGO_METADATA}" target_directory)
    else()
        message(WARNING "cargo metadata failed; assuming ${RUST_CRATE_DIR}/target")
        set(CARGO_TARGET_DIR ${RUST_CRATE_DIR}/target)
    endif()
    set(RUST_LIBRARY ${CARGO_TARGET_DIR}/debug/libdash_sdk_bindings.a)
endif()
message(STATUS "Rust library: ${RUST_LIBRARY}")
if(NOT EXISTS ${RUST_LIBRARY})
    message(FATAL_ERROR "Rust library not found at ${RUST_LIBRARY}; run 'cargo build' in ${RUST_CRATE_DIR} first")
endif()
find_package(JNI REQUIRED)
include_directories(${JNI_INCLUDE_DIRS}, ${CMAKE_SOURCE_DIR})
# Create an executable that links C and Rust code
add_library(sdklib SHARED ${C_SOURCE_FILE})

# Check if building on macOS
if(APPLE)
    if(NOT DEFINED MACOS_VERSION)
        set(MACOS_VERSION "14.5")
    endif()
    #option(MACOS_VERSION "Minimum macOS version to target" "14.5")
    set(CMAKE_OSX_DEPLOYMENT_TARGET ${MACOS_VERSION})
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mmacosx-version-min=${MACOS_VERSION}")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mmacosx-version-min=${MACOS_VERSION}")
    # Find the Core Foundation framework
    find_library(CORE_FOUNDATION_FRAMEWORK CoreFoundation)
    if(NOT CORE_FOUNDATION_FRAMEWORK)
        message(FATAL_ERROR "CoreFoundation framework not found")
    endif()

    # Find the Security framework
    find_library(SECURITY_FRAMEWORK Security)
    if(NOT SECURITY_FRAMEWORK)
        message(FATAL_ERROR "Security framework not found")
    endif()

    # Link the frameworks only on macOS
    target_link_libraries(sdklib PRIVATE "${CORE_FOUNDATION_FRAMEWORK}" "${SECURITY_FRAMEWORK}" ${RUST_LIBRARY})
else()
    target_link_libraries(sdklib PRIVATE ${RUST_LIBRARY})
endif()

