cmake_minimum_required(VERSION 3.22.1)

set(APP_GLUE_DIR ${ANDROID_NDK}/sources/android/native_app_glue)
include_directories(${APP_GLUE_DIR})
add_library(app-glue STATIC ${APP_GLUE_DIR}/android_native_app_glue.c)

add_definitions(-DXR_USE_PLATFORM_ANDROID)
add_definitions(-DXR_USE_GRAPHICS_API_OPENGL_ES)
add_definitions(-DXR_USE_GRAPHICS_API_VULKAN)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -u ANativeActivity_onCreate")

file(GLOB LOCAL_HEADERS "app/*.h")
file(GLOB LOCAL_SOURCE "app/*.cpp")
file(GLOB VULKAN_SHADERS "app/vulkan_shaders/*.glsl")

link_directories(openxr_loader/${ANDROID_ABI})
include_directories(openxr_loader/include)
link_directories(build/Oboe/prefab/modules/oboe/libs/android.${ANDROID_ABI})
include_directories(build/Oboe/prefab/modules/oboe/include)
include_directories(ffmpeg-include)

foreach(lib avutil swresample swscale avcodec avformat)
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/native-libs/${ANDROID_ABI}/lib${lib}.so)
endforeach()

add_library(player SHARED
    ${LOCAL_SOURCE}
    ${LOCAL_HEADERS}
    ${VULKAN_SHADERS}
    openxr_loader/include/common/gfxwrapper_opengl.c
)

target_link_libraries(player
    oboe openxr_loader
    avformat avcodec swresample swscale avutil
    android mediandk vulkan EGL GLESv3 app-glue log
)
