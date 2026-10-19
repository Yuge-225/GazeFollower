#include <jni.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>

#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct Engine {
    std::unique_ptr<MNN::Interpreter> interpreter;
    MNN::Session* session = nullptr;
    std::mutex mutex;
};

void throwJava(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message.c_str());
}

bool copyInput(JNIEnv* env, jfloatArray source, MNN::Tensor* destination,
               const char* name, int expectedCount) {
    if (source == nullptr || destination == nullptr) {
        throwJava(env, std::string("Missing MNN input: ") + name);
        return false;
    }
    const jsize count = env->GetArrayLength(source);
    if (count != expectedCount || destination->elementSize() != expectedCount) {
        throwJava(env, std::string("Unexpected shape for MNN input: ") + name);
        return false;
    }
    std::vector<float> values(static_cast<size_t>(count));
    env->GetFloatArrayRegion(source, 0, count, values.data());
    MNN::Tensor host(destination, MNN::Tensor::TENSORFLOW);
    std::copy(values.begin(), values.end(), host.host<float>());
    destination->copyFromHostTensor(&host);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_gazefollower_mobile_inference_MnnGazeEstimator_nativeCreate(
        JNIEnv* env, jobject, jbyteArray modelBytes) {
    if (modelBytes == nullptr) {
        throwJava(env, "base.mnn asset is empty");
        return 0;
    }
    const jsize size = env->GetArrayLength(modelBytes);
    std::vector<uint8_t> model(static_cast<size_t>(size));
    env->GetByteArrayRegion(modelBytes, 0, size, reinterpret_cast<jbyte*>(model.data()));

    auto engine = std::make_unique<Engine>();
    engine->interpreter.reset(MNN::Interpreter::createFromBuffer(model.data(), model.size()));
    if (!engine->interpreter) {
        throwJava(env, "MNN could not parse base.mnn");
        return 0;
    }
    MNN::ScheduleConfig config;
    config.type = MNN_FORWARD_CPU;
    config.numThread = 4;
    MNN::BackendConfig backendConfig;
    backendConfig.precision = MNN::BackendConfig::Precision_Low;
    config.backendConfig = &backendConfig;
    engine->session = engine->interpreter->createSession(config);
    if (!engine->session) {
        throwJava(env, "MNN could not create the CPU session");
        return 0;
    }
    engine->interpreter->releaseModel();
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_gazefollower_mobile_inference_MnnGazeEstimator_nativeInfer(
        JNIEnv* env, jobject, jlong handle, jfloatArray face, jfloatArray left,
        jfloatArray right, jfloatArray rect) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (engine == nullptr) {
        throwJava(env, "MNN engine is closed");
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(engine->mutex);
    auto* interpreter = engine->interpreter.get();
    if (!copyInput(env, face, interpreter->getSessionInput(engine->session, "face"),
                   "face", 224 * 224 * 3) ||
        !copyInput(env, left, interpreter->getSessionInput(engine->session, "left"),
                   "left", 112 * 112 * 3) ||
        !copyInput(env, right, interpreter->getSessionInput(engine->session, "right"),
                   "right", 112 * 112 * 3) ||
        !copyInput(env, rect, interpreter->getSessionInput(engine->session, "rect"),
                   "rect", 12)) {
        return nullptr;
    }
    const auto code = interpreter->runSession(engine->session);
    if (code != MNN::NO_ERROR) {
        throwJava(env, "MNN runSession failed with code " + std::to_string(code));
        return nullptr;
    }
    auto* output = interpreter->getSessionOutput(engine->session, "output_0");
    if (output == nullptr || output->elementSize() < 2) {
        throwJava(env, "Missing or invalid MNN output_0");
        return nullptr;
    }
    std::unique_ptr<MNN::Tensor> host(MNN::Tensor::createHostTensorFromDevice(output, true));
    const int count = host->elementSize();
    jfloatArray result = env->NewFloatArray(count);
    env->SetFloatArrayRegion(result, 0, count, host->host<float>());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_gazefollower_mobile_inference_MnnGazeEstimator_nativeClose(
        JNIEnv*, jobject, jlong handle) {
    auto* engine = reinterpret_cast<Engine*>(handle);
    if (engine == nullptr) return;
    if (engine->session != nullptr) {
        engine->interpreter->releaseSession(engine->session);
    }
    delete engine;
}
