cmake_minimum_required(VERSION 3.22.1)

project("mihon_ocr")

# Set C++ standard
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find required Android packages
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

include(FetchContent)

# Fetch Abseil (required for LiteRT C++ API)
FetchContent_Declare(
    absl
    GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
    GIT_TAG 20240722.0
)
set(ABSL_PROPAGATE_CXX_STD ON)
set(BUILD_TESTING OFF)
FetchContent_MakeAvailable(absl)

# Fetch FlatBuffers (required by LiteRT C++ API)
FetchContent_Declare(
    flatbuffers
    GIT_REPOSITORY https://github.com/google/flatbuffers.git
    GIT_TAG v24.3.25
)
set(FLATBUFFERS_BUILD_TESTS OFF)
set(FLATBUFFERS_INSTALL OFF)
FetchContent_MakeAvailable(flatbuffers)

# Fetch Pre-built LiteRT Next
FetchContent_Declare(
        litert_prebuilt
        URL "https://github.com/mihon-ocr/litert-cpp-dist/releases/download/v2.1.0rc1/litert_android_arm64.zip"
)
FetchContent_Populate(litert_prebuilt)

# Define where the extracted LiteRT files are
set(LITERT_DIST_DIR "${litert_prebuilt_SOURCE_DIR}")

# Create LiteRT C API imported target (the shared library)
add_library(litert_c_api SHARED IMPORTED GLOBAL)
set_target_properties(litert_c_api PROPERTIES
    IMPORTED_LOCATION "${LITERT_DIST_DIR}/lib/libLiteRt.so"
    INTERFACE_INCLUDE_DIRECTORIES "${LITERT_DIST_DIR}/include"
    IMPORTED_NO_SONAME TRUE
)

# Note: libLiteRtOpenClAccelerator.so is NOT linked at compile time.
# It is dynamically loaded by LiteRT at runtime using dlopen().
# See POST_BUILD command below that copies it to the output directory.

# Collect C++ source files from litert/cc/
file(GLOB_RECURSE LITERT_CC_SOURCES
    "${LITERT_DIST_DIR}/include/litert/cc/*.cc"
)

# NOTE: We do NOT compile litert/c/*.cc files because they have internal
# dependencies on headers not included in the prebuilt package.
# Instead, we use stub implementations for the logger functions.

# Combine all sources (only CC wrapper sources + our logger stub)
list(APPEND LITERT_ALL_SOURCES ${LITERT_CC_SOURCES})
list(APPEND LITERT_ALL_SOURCES "${CMAKE_CURRENT_SOURCE_DIR}/litert_logger_stub.cpp")

# Exclude darwinn (Apple-specific TPU) and webnn (web-specific)
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*darwinn.*")
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*webnn.*")
# Exclude Windows-specific files
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*_win\\.cc")
# Exclude empty placeholder file
list(FILTER LITERT_ALL_SOURCES EXCLUDE REGEX ".*empty\\.cc")

# Create C++ wrapper static library
add_library(litert_cc_api STATIC ${LITERT_ALL_SOURCES})
target_include_directories(litert_cc_api PUBLIC "${LITERT_DIST_DIR}/include")
target_compile_options(litert_cc_api PRIVATE
    -Wno-error=deprecated-declarations
    -Wno-deprecated-declarations
)
target_link_libraries(litert_cc_api PUBLIC
    litert_c_api
    absl::status
    absl::statusor
    absl::strings
    absl::span
    absl::log
    absl::check
    absl::hash
    absl::flat_hash_map
    absl::cleanup
    absl::str_format
    absl::synchronization
    flatbuffers
)

# Build Project
add_library(mihon_ocr SHARED
        ocr_native.cpp
        ocr_inference.cpp
        text_postprocessor.cpp
        vocab_data.cpp
)

# Link against the imported targets
target_link_libraries(mihon_ocr
        # Android system libs
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        # LiteRT C++ API (which transitively links to litert_c_api and abseil)
        litert_cc_api
)

add_custom_command(TARGET mihon_ocr POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${LITERT_DIST_DIR}/lib/libLiteRtOpenClAccelerator.so"
        "$<TARGET_FILE_DIR:mihon_ocr>/libLiteRtOpenClAccelerator.so"
    COMMENT "Copying GPU accelerator library for runtime loading"
)

add_custom_command(TARGET mihon_ocr POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${LITERT_DIST_DIR}/lib/libLiteRt.so"
        "$<TARGET_FILE_DIR:mihon_ocr>/libLiteRt.so"
        COMMENT "Copying core LiteRT library for runtime loading"
)
