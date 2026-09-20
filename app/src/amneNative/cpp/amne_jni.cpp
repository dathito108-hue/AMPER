#include <jni.h>

#include <cstdint>
#include <vector>

#include "amne_kernels.h"

namespace {

bool require_condition(JNIEnv* env, bool condition, const char* message) {
    if (condition) {
        return true;
    }
    jclass clazz = env->FindClass("java/lang/IllegalArgumentException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message);
    }
    return false;
}

jfloatArray make_float_array(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray output = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (output == nullptr) {
        return nullptr;
    }
    if (!values.empty()) {
        env->SetFloatArrayRegion(
            output,
            0,
            static_cast<jsize>(values.size()),
            values.data()
        );
    }
    return output;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_abiVersion(
    JNIEnv*,
    jobject
) {
    return amne::kAbiVersion;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_dotF32(
    JNIEnv* env,
    jobject,
    jfloatArray left,
    jfloatArray right
) {
    if (!require_condition(env, left != nullptr && right != nullptr, "AMNE dot arrays are required")) {
        return 0.0f;
    }
    const jsize left_count = env->GetArrayLength(left);
    const jsize right_count = env->GetArrayLength(right);
    if (!require_condition(env, left_count == right_count, "AMNE dot vector length mismatch")) {
        return 0.0f;
    }

    jboolean left_copy = JNI_FALSE;
    jboolean right_copy = JNI_FALSE;
    jfloat* left_ptr = env->GetFloatArrayElements(left, &left_copy);
    if (left_ptr == nullptr) {
        return 0.0f;
    }
    jfloat* right_ptr = env->GetFloatArrayElements(right, &right_copy);
    if (right_ptr == nullptr) {
        env->ReleaseFloatArrayElements(left, left_ptr, JNI_ABORT);
        return 0.0f;
    }

    const double result = amne::dot_f32(
        left_ptr,
        right_ptr,
        static_cast<std::size_t>(left_count)
    );
    env->ReleaseFloatArrayElements(right, right_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(left, left_ptr, JNI_ABORT);
    return static_cast<jfloat>(result);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_matVecF32(
    JNIEnv* env,
    jobject,
    jfloatArray matrix,
    jint rows,
    jint columns,
    jfloatArray vector
) {
    if (!require_condition(
            env,
            matrix != nullptr && vector != nullptr && rows > 0 && columns > 0,
            "AMNE F32 matvec arguments are invalid")) {
        return nullptr;
    }

    const jsize matrix_count = env->GetArrayLength(matrix);
    const jsize vector_count = env->GetArrayLength(vector);
    std::vector<float> output(static_cast<std::size_t>(rows));

    jfloat* matrix_ptr = env->GetFloatArrayElements(matrix, nullptr);
    if (matrix_ptr == nullptr) {
        return nullptr;
    }
    jfloat* vector_ptr = env->GetFloatArrayElements(vector, nullptr);
    if (vector_ptr == nullptr) {
        env->ReleaseFloatArrayElements(matrix, matrix_ptr, JNI_ABORT);
        return nullptr;
    }

    const bool ok = amne::matvec_f32(
        matrix_ptr,
        static_cast<std::size_t>(matrix_count),
        static_cast<int>(rows),
        static_cast<int>(columns),
        vector_ptr,
        static_cast<std::size_t>(vector_count),
        output.data(),
        output.size()
    );
    env->ReleaseFloatArrayElements(vector, vector_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(matrix, matrix_ptr, JNI_ABORT);

    if (!require_condition(env, ok, "AMNE F32 matvec shape mismatch")) {
        return nullptr;
    }
    return make_float_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_matVecQ40(
    JNIEnv* env,
    jobject,
    jbyteArray matrix,
    jint rows,
    jint columns,
    jfloatArray vector
) {
    if (!require_condition(
            env,
            matrix != nullptr && vector != nullptr && rows > 0 && columns > 0,
            "AMNE Q4_0 matvec arguments are invalid")) {
        return nullptr;
    }

    const jsize matrix_bytes = env->GetArrayLength(matrix);
    const jsize vector_count = env->GetArrayLength(vector);
    std::vector<float> output(static_cast<std::size_t>(rows));

    jbyte* matrix_ptr = env->GetByteArrayElements(matrix, nullptr);
    if (matrix_ptr == nullptr) {
        return nullptr;
    }
    jfloat* vector_ptr = env->GetFloatArrayElements(vector, nullptr);
    if (vector_ptr == nullptr) {
        env->ReleaseByteArrayElements(matrix, matrix_ptr, JNI_ABORT);
        return nullptr;
    }

    const bool ok = amne::matvec_q4_0(
        reinterpret_cast<const std::uint8_t*>(matrix_ptr),
        static_cast<std::size_t>(matrix_bytes),
        static_cast<int>(rows),
        static_cast<int>(columns),
        vector_ptr,
        static_cast<std::size_t>(vector_count),
        output.data(),
        output.size()
    );
    env->ReleaseFloatArrayElements(vector, vector_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(matrix, matrix_ptr, JNI_ABORT);

    if (!require_condition(env, ok, "AMNE Q4_0 matvec shape or block mismatch")) {
        return nullptr;
    }
    return make_float_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_matVecQ80(
    JNIEnv* env,
    jobject,
    jbyteArray matrix,
    jint rows,
    jint columns,
    jfloatArray vector
) {
    if (!require_condition(
            env,
            matrix != nullptr && vector != nullptr && rows > 0 && columns > 0,
            "AMNE Q8_0 matvec arguments are invalid")) {
        return nullptr;
    }

    const jsize matrix_bytes = env->GetArrayLength(matrix);
    const jsize vector_count = env->GetArrayLength(vector);
    std::vector<float> output(static_cast<std::size_t>(rows));

    jbyte* matrix_ptr = env->GetByteArrayElements(matrix, nullptr);
    if (matrix_ptr == nullptr) {
        return nullptr;
    }
    jfloat* vector_ptr = env->GetFloatArrayElements(vector, nullptr);
    if (vector_ptr == nullptr) {
        env->ReleaseByteArrayElements(matrix, matrix_ptr, JNI_ABORT);
        return nullptr;
    }

    const bool ok = amne::matvec_q8_0(
        reinterpret_cast<const std::int8_t*>(matrix_ptr),
        static_cast<std::size_t>(matrix_bytes),
        static_cast<int>(rows),
        static_cast<int>(columns),
        vector_ptr,
        static_cast<std::size_t>(vector_count),
        output.data(),
        output.size()
    );
    env->ReleaseFloatArrayElements(vector, vector_ptr, JNI_ABORT);
    env->ReleaseByteArrayElements(matrix, matrix_ptr, JNI_ABORT);

    if (!require_condition(env, ok, "AMNE Q8_0 matvec shape or block mismatch")) {
        return nullptr;
    }
    return make_float_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_rmsNormF32(
    JNIEnv* env,
    jobject,
    jfloatArray input,
    jfloatArray weight,
    jfloat epsilon
) {
    if (!require_condition(env, input != nullptr && weight != nullptr, "AMNE RMSNorm arrays are required")) {
        return nullptr;
    }
    const jsize count = env->GetArrayLength(input);
    if (!require_condition(env, count == env->GetArrayLength(weight), "AMNE RMSNorm width mismatch")) {
        return nullptr;
    }
    std::vector<float> output(static_cast<std::size_t>(count));

    jfloat* input_ptr = env->GetFloatArrayElements(input, nullptr);
    if (input_ptr == nullptr) {
        return nullptr;
    }
    jfloat* weight_ptr = env->GetFloatArrayElements(weight, nullptr);
    if (weight_ptr == nullptr) {
        env->ReleaseFloatArrayElements(input, input_ptr, JNI_ABORT);
        return nullptr;
    }

    const bool ok = amne::rms_norm_f32(
        input_ptr,
        weight_ptr,
        static_cast<std::size_t>(count),
        static_cast<float>(epsilon),
        output.data()
    );
    env->ReleaseFloatArrayElements(weight, weight_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(input, input_ptr, JNI_ABORT);

    if (!require_condition(env, ok, "AMNE RMSNorm arguments are invalid")) {
        return nullptr;
    }
    return make_float_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_siluF32(
    JNIEnv* env,
    jobject,
    jfloatArray input
) {
    if (!require_condition(env, input != nullptr, "AMNE SiLU input is required")) {
        return nullptr;
    }
    const jsize count = env->GetArrayLength(input);
    std::vector<float> output(static_cast<std::size_t>(count));
    jfloat* input_ptr = env->GetFloatArrayElements(input, nullptr);
    if (input_ptr == nullptr) {
        return nullptr;
    }
    const bool ok = amne::silu_f32(
        input_ptr,
        static_cast<std::size_t>(count),
        output.data()
    );
    env->ReleaseFloatArrayElements(input, input_ptr, JNI_ABORT);
    if (!require_condition(env, ok, "AMNE SiLU arguments are invalid")) {
        return nullptr;
    }
    return make_float_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_swiGluF32(
    JNIEnv* env,
    jobject,
    jfloatArray gate,
    jfloatArray up
) {
    if (!require_condition(env, gate != nullptr && up != nullptr, "AMNE SwiGLU arrays are required")) {
        return nullptr;
    }
    const jsize count = env->GetArrayLength(gate);
    if (!require_condition(env, count == env->GetArrayLength(up), "AMNE SwiGLU width mismatch")) {
        return nullptr;
    }
    std::vector<float> output(static_cast<std::size_t>(count));

    jfloat* gate_ptr = env->GetFloatArrayElements(gate, nullptr);
    if (gate_ptr == nullptr) {
        return nullptr;
    }
    jfloat* up_ptr = env->GetFloatArrayElements(up, nullptr);
    if (up_ptr == nullptr) {
        env->ReleaseFloatArrayElements(gate, gate_ptr, JNI_ABORT);
        return nullptr;
    }
    const bool ok = amne::swiglu_f32(
        gate_ptr,
        up_ptr,
        static_cast<std::size_t>(count),
        output.data()
    );
    env->ReleaseFloatArrayElements(up, up_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(gate, gate_ptr, JNI_ABORT);

    if (!require_condition(env, ok, "AMNE SwiGLU arguments are invalid")) {
        return nullptr;
    }
    return make_float_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_softmaxF32(
    JNIEnv* env,
    jobject,
    jfloatArray input
) {
    if (!require_condition(env, input != nullptr, "AMNE Softmax input is required")) {
        return nullptr;
    }
    const jsize count = env->GetArrayLength(input);
    std::vector<float> output(static_cast<std::size_t>(count));
    jfloat* input_ptr = env->GetFloatArrayElements(input, nullptr);
    if (input_ptr == nullptr) {
        return nullptr;
    }
    const bool ok = amne::softmax_f32(
        input_ptr,
        static_cast<std::size_t>(count),
        output.data()
    );
    env->ReleaseFloatArrayElements(input, input_ptr, JNI_ABORT);
    if (!require_condition(env, ok, "AMNE Softmax arguments are invalid")) {
        return nullptr;
    }
    return make_float_array(env, output);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_backend_AmneNativeBridge_ropeF32(
    JNIEnv* env,
    jobject,
    jfloatArray input,
    jint position,
    jfloat theta
) {
    if (!require_condition(env, input != nullptr, "AMNE RoPE input is required")) {
        return nullptr;
    }
    const jsize count = env->GetArrayLength(input);
    std::vector<float> output(static_cast<std::size_t>(count));
    jfloat* input_ptr = env->GetFloatArrayElements(input, nullptr);
    if (input_ptr == nullptr) {
        return nullptr;
    }
    const bool ok = amne::rope_f32(
        input_ptr,
        static_cast<std::size_t>(count),
        static_cast<int>(position),
        static_cast<float>(theta),
        output.data()
    );
    env->ReleaseFloatArrayElements(input, input_ptr, JNI_ABORT);
    if (!require_condition(env, ok, "AMNE RoPE arguments are invalid")) {
        return nullptr;
    }
    return make_float_array(env, output);
}
