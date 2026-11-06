#include <jni.h>
#include <cstdint>
#include <new>
#include <vector>
#include <speex/speex_preprocess.h>

namespace {
struct Denoiser {
    int channels = 0;
    int frameSamples = 0;
    SpeexPreprocessState* processors[2] = {nullptr, nullptr};
    std::vector<spx_int16_t> samples;
};

void destroy(Denoiser* handle) {
    if (!handle) return;
    for (auto*& processor : handle->processors) {
        if (processor) {
            speex_preprocess_state_destroy(processor);
            processor = nullptr;
        }
    }
    delete handle;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_livenexus_controller_SpeexNoiseSuppressor_nativeCreate(
    JNIEnv*, jobject, jint sampleRate, jint channels, jint frameSamples) {
    if (sampleRate < 8000 || sampleRate > 96000 ||
        channels < 1 || channels > 2 || frameSamples < 80 || frameSamples > 2048) {
        return 0;
    }
    auto* handle = new (std::nothrow) Denoiser();
    if (!handle) return 0;
    handle->channels = channels;
    handle->frameSamples = frameSamples;
    handle->samples.resize(static_cast<size_t>(frameSamples));
    for (int c = 0; c < channels; ++c) {
        handle->processors[c] = speex_preprocess_state_init(frameSamples, sampleRate);
        if (!handle->processors[c]) {
            destroy(handle);
            return 0;
        }
        int enableDenoise = 1;
        int noiseSuppressionDb = -18;
        int disableAgc = 0;
        speex_preprocess_ctl(handle->processors[c], SPEEX_PREPROCESS_SET_DENOISE, &enableDenoise);
        speex_preprocess_ctl(handle->processors[c], SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &noiseSuppressionDb);
        speex_preprocess_ctl(handle->processors[c], SPEEX_PREPROCESS_SET_AGC, &disableAgc);
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_livenexus_controller_SpeexNoiseSuppressor_nativeProcess(
    JNIEnv* env, jobject, jlong ptr, jbyteArray buffer, jint length) {
    auto* handle = reinterpret_cast<Denoiser*>(ptr);
    if (!handle || !buffer || length <= 0 ||
        length != handle->frameSamples * handle->channels * 2 ||
        env->GetArrayLength(buffer) < length) {
        return JNI_FALSE;
    }
    jbyte* raw = env->GetByteArrayElements(buffer, nullptr);
    if (!raw) return JNI_FALSE;
    auto* data = reinterpret_cast<uint8_t*>(raw);
    const int frameSamples = handle->frameSamples;
    const int channels = handle->channels;
    for (int c = 0; c < channels; ++c) {
        for (int i = 0; i < frameSamples; ++i) {
            const int offset = (i * channels + c) * 2;
            const uint16_t packed = static_cast<uint16_t>(data[offset]) |
                (static_cast<uint16_t>(data[offset + 1]) << 8);
            handle->samples[i] = static_cast<int16_t>(packed);
        }
        speex_preprocess_run(handle->processors[c], handle->samples.data());
        for (int i = 0; i < frameSamples; ++i) {
            const int offset = (i * channels + c) * 2;
            const uint16_t packed = static_cast<uint16_t>(handle->samples[i]);
            data[offset] = static_cast<uint8_t>(packed & 0xFF);
            data[offset + 1] = static_cast<uint8_t>((packed >> 8) & 0xFF);
        }
    }
    env->ReleaseByteArrayElements(buffer, raw, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_livenexus_controller_SpeexNoiseSuppressor_nativeDestroy(
    JNIEnv*, jobject, jlong ptr) {
    destroy(reinterpret_cast<Denoiser*>(ptr));
}
