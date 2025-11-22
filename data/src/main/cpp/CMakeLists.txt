cmake_minimum_required(VERSION 3.22.1)

project("mihon_ocr")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Find required packages
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# Add source files
add_library(
    mihon_ocr
    SHARED
    ocr_native.cpp
    text_postprocessor.cpp
    vocab_data.cpp
)

# Include directories for our headers
target_include_directories(
    mihon_ocr
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link libraries
target_link_libraries(
    mihon_ocr
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
