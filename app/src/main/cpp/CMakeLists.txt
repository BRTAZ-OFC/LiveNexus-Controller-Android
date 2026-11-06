cmake_minimum_required(VERSION 3.22.1)
project(livenexus_speex LANGUAGES C CXX)

set(SPEEX_SRC "${CMAKE_CURRENT_SOURCE_DIR}/vendor/speexdsp/libspeexdsp")

add_library(livenexus_speex SHARED
    speex_jni.cpp
    "${SPEEX_SRC}/preprocess.c"
    "${SPEEX_SRC}/mdf.c"
    "${SPEEX_SRC}/fftwrap.c"
    "${SPEEX_SRC}/filterbank.c"
    "${SPEEX_SRC}/kiss_fft.c"
    "${SPEEX_SRC}/kiss_fftr.c"
    "${SPEEX_SRC}/smallft.c"
    "${SPEEX_SRC}/jitter.c"
    "${SPEEX_SRC}/resample.c"
    "${SPEEX_SRC}/buffer.c"
    "${SPEEX_SRC}/scal.c"
)

target_include_directories(livenexus_speex PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/vendor/speexdsp/include"
    "${SPEEX_SRC}"
)
target_compile_definitions(livenexus_speex PRIVATE FLOATING_POINT USE_KISS_FFT EXPORT=)
target_compile_features(livenexus_speex PRIVATE cxx_std_17)
target_link_libraries(livenexus_speex log m)
