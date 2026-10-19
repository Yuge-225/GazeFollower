cmake_minimum_required(VERSION 3.22.1)
project(gazefollower_mnn LANGUAGES CXX)

set(MNN_VERSION "3.6.1")
set(MNN_RELEASE_SHA256 "46dc7e86d45b8d4e957db81d2603e0b7f6c9ce9b84092ffdcee1b843cbfc9d71")
set(MNN_DOWNLOAD_DIR "${CMAKE_BINARY_DIR}/mnn-${MNN_VERSION}")
set(MNN_ARCHIVE "${MNN_DOWNLOAD_DIR}/mnn-android.zip")
set(MNN_PACKAGE_DIR "${MNN_DOWNLOAD_DIR}/package")
set(MNN_INCLUDE_DIR "${MNN_DOWNLOAD_DIR}/include")

file(MAKE_DIRECTORY "${MNN_DOWNLOAD_DIR}" "${MNN_INCLUDE_DIR}/MNN")
if(NOT EXISTS "${MNN_ARCHIVE}")
    file(DOWNLOAD
        "https://github.com/alibaba/MNN/releases/download/${MNN_VERSION}/mnn_${MNN_VERSION}_android_armv7_armv8_cpu_opencl_vulkan.zip"
        "${MNN_ARCHIVE}"
        EXPECTED_HASH "SHA256=${MNN_RELEASE_SHA256}"
        TLS_VERIFY ON
        SHOW_PROGRESS
    )
endif()
if(NOT EXISTS "${MNN_PACKAGE_DIR}/mnn_${MNN_VERSION}_android_armv7_armv8_cpu_opencl_vulkan/armeabi-v7a/libMNN.so")
    file(MAKE_DIRECTORY "${MNN_PACKAGE_DIR}")
    file(ARCHIVE_EXTRACT INPUT "${MNN_ARCHIVE}" DESTINATION "${MNN_PACKAGE_DIR}")
endif()

function(download_mnn_header filename sha256)
    set(destination "${MNN_INCLUDE_DIR}/MNN/${filename}")
    if(NOT EXISTS "${destination}")
        file(DOWNLOAD
            "https://raw.githubusercontent.com/alibaba/MNN/${MNN_VERSION}/include/MNN/${filename}"
            "${destination}"
            EXPECTED_HASH "SHA256=${sha256}"
            TLS_VERIFY ON
        )
    endif()
endfunction()

download_mnn_header("Interpreter.hpp" "0c367286c5906572218ddfa6426007ca748cbf5433f6cd7c7c71ae3300b1f986")
download_mnn_header("Tensor.hpp" "6f28b4d0338c6760582ed8d5632fa0c3691bdfacc421753b6d86407466a7bf5c")
download_mnn_header("ErrorCode.hpp" "de7d2290e4e2f024ae51707298c30721a283367eb90a7abac113d27248d56dd0")
download_mnn_header("MNNForwardType.h" "b9c670a78f3ec42c81328429004fdd6c3ff1a172a52373dd09945a678e5ea167")
download_mnn_header("HalideRuntime.h" "f1d35170fa3fc1c7433ca1d18ca5d016ad230f26a0ecfba91b9a7ef9a49f7a37")
download_mnn_header("MNNDefine.h" "481f2ff7a4dd29ce779384b8f5169c8b1aee8ebd8f0a9ca4c2f8444de45f9b6f")

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
    "${MNN_PACKAGE_DIR}/mnn_${MNN_VERSION}_android_armv7_armv8_cpu_opencl_vulkan/${ANDROID_ABI}/libMNN.so"
)

add_library(gazefollower_mnn SHARED gaze_mnn_jni.cpp)
target_include_directories(gazefollower_mnn PRIVATE "${MNN_INCLUDE_DIR}")
target_link_libraries(gazefollower_mnn PRIVATE MNN log android)
