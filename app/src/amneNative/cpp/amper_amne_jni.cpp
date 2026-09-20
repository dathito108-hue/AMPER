#include <jni.h>
#include <arm_neon.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <vector>

namespace {

constexpr const char* kTag = "AMPER-AMNE";

void throwIllegalArgument(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

bool requireFiniteVector(const float* data, jsize size) {
    for (jsize i = 0; i < size; ++i) {
        if (!std::isfinite(data[i])) {
            return false;
        }
    }
    return true;
}

float halfToFloat(std::uint16_t bits) {
    const std::uint32_t sign = (bits >> 15U) & 0x1U;
    const std::uint32_t exponent = (bits >> 10U) & 0x1fU;
    const std::uint32_t fraction = bits & 0x3ffU;

    std::uint32_t floatBits = 0U;
    if (exponent == 0U && fraction == 0U) {
        floatBits = sign << 31U;
    } else if (exponent == 0U) {
        std::uint32_t mantissa = fraction;
        int shift = 0;
        while ((mantissa & 0x400U) == 0U) {
            mantissa <<= 1U;
            ++shift;
        }
        mantissa &= 0x3ffU;
        const std::uint32_t adjustedExponent =
            static_cast<std::uint32_t>(127 - 15 - shift + 1);
        floatBits =
            (sign << 31U) |
            (adjustedExponent << 23U) |
            (mantissa << 13U);
    } else if (exponent == 0x1fU) {
        floatBits =
            (sign << 31U) |
            0x7f800000U |
            (fraction << 13U);
    } else {
        const std::uint32_t adjustedExponent = exponent - 15U + 127U;
        floatBits =
            (sign << 31U) |
            (adjustedExponent << 23U) |
            (fraction << 13U);
    }

    float value = 0.0f;
    static_assert(sizeof(value) == sizeof(floatBits));
    std::memcpy(&value, &floatBits, sizeof(value));
    return value;
}

float dotNeon(const float* left, const float* right, int length) {
    float32x4_t acc = vdupq_n_f32(0.0f);
    int index = 0;
    for (; index + 4 <= length; index += 4) {
        const float32x4_t a = vld1q_f32(left + index);
        const float32x4_t b = vld1q_f32(right + index);
        acc = vfmaq_f32(acc, a, b);
    }
    float sum = vaddvq_f32(acc);
    for (; index < length; ++index) {
        sum += left[index] * right[index];
    }
    return sum;
}

std::pair<int, int> scaleMinK4(
    const std::uint8_t* scales,
    int index
) {
    if (index < 4) {
        return {
            static_cast<int>(scales[index] & 63U),
            static_cast<int>(scales[index + 4] & 63U)
        };
    }
    return {
        static_cast<int>(
            (scales[index + 4] & 0x0fU) |
            ((scales[index - 4] >> 6U) << 4U)
        ),
        static_cast<int>(
            (scales[index + 4] >> 4U) |
            ((scales[index] >> 6U) << 4U)
        )
    };
}

float readHalf(const std::uint8_t* bytes) {
    const std::uint16_t bits =
        static_cast<std::uint16_t>(bytes[0]) |
        (static_cast<std::uint16_t>(bytes[1]) << 8U);
    return halfToFloat(bits);
}

bool validateKQuantShape(
    JNIEnv* env,
    jbyteArray matrixArray,
    jint rows,
    jint columns,
    jfloatArray vectorArray,
    jint blockElements,
    jint blockBytes,
    const char* label
) {
    if (matrixArray == nullptr || vectorArray == nullptr || rows <= 0 || columns <= 0) {
        throwIllegalArgument(env, label);
        return false;
    }
    if ((columns % blockElements) != 0 || env->GetArrayLength(vectorArray) != columns) {
        throwIllegalArgument(env, "AMNE K-quant matvec shape mismatch");
        return false;
    }
    const jlong blocksPerRow = columns / blockElements;
    const jlong expectedBytes =
        static_cast<jlong>(rows) * blocksPerRow * static_cast<jlong>(blockBytes);
    if (env->GetArrayLength(matrixArray) != expectedBytes) {
        throwIllegalArgument(env, "AMNE K-quant matrix byte length mismatch");
        return false;
    }
    return true;
}

jfloatArray makeFloatArray(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetFloatArrayRegion(
        result,
        0,
        static_cast<jsize>(values.size()),
        values.data()
    );
    return result;
}

}  // namespace

extern "C"
JNIEXPORT jfloat JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeDotF32(
    JNIEnv* env,
    jobject,
    jfloatArray leftArray,
    jfloatArray rightArray
) {
    if (leftArray == nullptr || rightArray == nullptr) {
        throwIllegalArgument(env, "AMNE dot arrays must not be null");
        return 0.0f;
    }
    const jsize leftSize = env->GetArrayLength(leftArray);
    const jsize rightSize = env->GetArrayLength(rightArray);
    if (leftSize != rightSize) {
        throwIllegalArgument(env, "AMNE dot vectors must have equal length");
        return 0.0f;
    }

    jfloat* left = env->GetFloatArrayElements(leftArray, nullptr);
    jfloat* right = env->GetFloatArrayElements(rightArray, nullptr);
    if (left == nullptr || right == nullptr) {
        if (left != nullptr) env->ReleaseFloatArrayElements(leftArray, left, JNI_ABORT);
        if (right != nullptr) env->ReleaseFloatArrayElements(rightArray, right, JNI_ABORT);
        return 0.0f;
    }

    const float result = dotNeon(left, right, leftSize);
    env->ReleaseFloatArrayElements(leftArray, left, JNI_ABORT);
    env->ReleaseFloatArrayElements(rightArray, right, JNI_ABORT);
    return result;
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeMatVecF32(
    JNIEnv* env,
    jobject,
    jfloatArray matrixArray,
    jint rows,
    jint columns,
    jfloatArray vectorArray
) {
    if (matrixArray == nullptr || vectorArray == nullptr || rows <= 0 || columns <= 0) {
        throwIllegalArgument(env, "AMNE matvec arguments are invalid");
        return nullptr;
    }
    const jlong expected =
        static_cast<jlong>(rows) * static_cast<jlong>(columns);
    if (
        env->GetArrayLength(matrixArray) != expected ||
        env->GetArrayLength(vectorArray) != columns
    ) {
        throwIllegalArgument(env, "AMNE matvec shape mismatch");
        return nullptr;
    }

    jfloat* matrix = env->GetFloatArrayElements(matrixArray, nullptr);
    jfloat* vector = env->GetFloatArrayElements(vectorArray, nullptr);
    if (matrix == nullptr || vector == nullptr) {
        if (matrix != nullptr) env->ReleaseFloatArrayElements(matrixArray, matrix, JNI_ABORT);
        if (vector != nullptr) env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
        return nullptr;
    }

    std::vector<float> output(static_cast<std::size_t>(rows));
    for (jint row = 0; row < rows; ++row) {
        output[static_cast<std::size_t>(row)] =
            dotNeon(matrix + static_cast<jlong>(row) * columns, vector, columns);
    }

    env->ReleaseFloatArrayElements(matrixArray, matrix, JNI_ABORT);
    env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeMatVecQ4_10(
    JNIEnv* env,
    jobject,
    jbyteArray matrixArray,
    jint rows,
    jint columns,
    jfloatArray vectorArray
) {
    if (matrixArray == nullptr || vectorArray == nullptr || rows <= 0 || columns <= 0) {
        throwIllegalArgument(env, "AMNE Q4_0 matvec arguments are invalid");
        return nullptr;
    }
    if ((columns % 32) != 0 || env->GetArrayLength(vectorArray) != columns) {
        throwIllegalArgument(env, "AMNE Q4_0 matvec shape mismatch");
        return nullptr;
    }
    const jint blocksPerRow = columns / 32;
    const jlong expectedBytes =
        static_cast<jlong>(rows) * static_cast<jlong>(blocksPerRow) * 18LL;
    if (env->GetArrayLength(matrixArray) != expectedBytes) {
        throwIllegalArgument(env, "AMNE Q4_0 matrix byte length mismatch");
        return nullptr;
    }

    jbyte* matrix = env->GetByteArrayElements(matrixArray, nullptr);
    jfloat* vector = env->GetFloatArrayElements(vectorArray, nullptr);
    if (matrix == nullptr || vector == nullptr) {
        if (matrix != nullptr) env->ReleaseByteArrayElements(matrixArray, matrix, JNI_ABORT);
        if (vector != nullptr) env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
        return nullptr;
    }

    std::vector<float> output(static_cast<std::size_t>(rows));
    std::size_t byteOffset = 0U;
    for (jint row = 0; row < rows; ++row) {
        double sum = 0.0;
        for (jint block = 0; block < blocksPerRow; ++block) {
            const auto b0 = static_cast<std::uint8_t>(matrix[byteOffset]);
            const auto b1 = static_cast<std::uint8_t>(matrix[byteOffset + 1U]);
            const std::uint16_t scaleBits =
                static_cast<std::uint16_t>(b0) |
                (static_cast<std::uint16_t>(b1) << 8U);
            const float scale = halfToFloat(scaleBits);
            const jint vectorBase = block * 32;

            for (jint packedIndex = 0; packedIndex < 16; ++packedIndex) {
                const auto packed =
                    static_cast<std::uint8_t>(matrix[byteOffset + 2U + packedIndex]);
                const int low = static_cast<int>(packed & 0x0fU) - 8;
                const int high = static_cast<int>((packed >> 4U) & 0x0fU) - 8;
                sum +=
                    static_cast<double>(scale * static_cast<float>(low)) *
                    static_cast<double>(vector[vectorBase + packedIndex]);
                sum +=
                    static_cast<double>(scale * static_cast<float>(high)) *
                    static_cast<double>(vector[vectorBase + 16 + packedIndex]);
            }
            byteOffset += 18U;
        }
        output[static_cast<std::size_t>(row)] = static_cast<float>(sum);
    }

    env->ReleaseByteArrayElements(matrixArray, matrix, JNI_ABORT);
    env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeMatVecQ8_10(
    JNIEnv* env,
    jobject,
    jbyteArray matrixArray,
    jint rows,
    jint columns,
    jfloatArray vectorArray
) {
    if (matrixArray == nullptr || vectorArray == nullptr || rows <= 0 || columns <= 0) {
        throwIllegalArgument(env, "AMNE Q8_0 matvec arguments are invalid");
        return nullptr;
    }
    if ((columns % 32) != 0 || env->GetArrayLength(vectorArray) != columns) {
        throwIllegalArgument(env, "AMNE Q8_0 matvec shape mismatch");
        return nullptr;
    }
    const jint blocksPerRow = columns / 32;
    const jlong expectedBytes =
        static_cast<jlong>(rows) * static_cast<jlong>(blocksPerRow) * 34LL;
    if (env->GetArrayLength(matrixArray) != expectedBytes) {
        throwIllegalArgument(env, "AMNE Q8_0 matrix byte length mismatch");
        return nullptr;
    }

    jbyte* matrix = env->GetByteArrayElements(matrixArray, nullptr);
    jfloat* vector = env->GetFloatArrayElements(vectorArray, nullptr);
    if (matrix == nullptr || vector == nullptr) {
        if (matrix != nullptr) env->ReleaseByteArrayElements(matrixArray, matrix, JNI_ABORT);
        if (vector != nullptr) env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
        return nullptr;
    }

    std::vector<float> output(static_cast<std::size_t>(rows));
    std::size_t byteOffset = 0U;
    for (jint row = 0; row < rows; ++row) {
        double sum = 0.0;
        for (jint block = 0; block < blocksPerRow; ++block) {
            const auto b0 = static_cast<std::uint8_t>(matrix[byteOffset]);
            const auto b1 = static_cast<std::uint8_t>(matrix[byteOffset + 1U]);
            const std::uint16_t scaleBits =
                static_cast<std::uint16_t>(b0) |
                (static_cast<std::uint16_t>(b1) << 8U);
            const float scale = halfToFloat(scaleBits);
            const jint vectorBase = block * 32;

            for (jint element = 0; element < 32; ++element) {
                const auto quantized =
                    static_cast<std::int8_t>(matrix[byteOffset + 2U + element]);
                sum +=
                    static_cast<double>(scale * static_cast<float>(quantized)) *
                    static_cast<double>(vector[vectorBase + element]);
            }
            byteOffset += 34U;
        }
        output[static_cast<std::size_t>(row)] = static_cast<float>(sum);
    }

    env->ReleaseByteArrayElements(matrixArray, matrix, JNI_ABORT);
    env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeMatVecQ4K(
    JNIEnv* env,
    jobject,
    jbyteArray matrixArray,
    jint rows,
    jint columns,
    jfloatArray vectorArray
) {
    if (!validateKQuantShape(
            env, matrixArray, rows, columns, vectorArray, 256, 144,
            "AMNE Q4_K matvec arguments are invalid")) {
        return nullptr;
    }

    jbyte* matrixRaw = env->GetByteArrayElements(matrixArray, nullptr);
    jfloat* vector = env->GetFloatArrayElements(vectorArray, nullptr);
    if (matrixRaw == nullptr || vector == nullptr) {
        if (matrixRaw != nullptr) env->ReleaseByteArrayElements(matrixArray, matrixRaw, JNI_ABORT);
        if (vector != nullptr) env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
        return nullptr;
    }

    const auto* matrix = reinterpret_cast<const std::uint8_t*>(matrixRaw);
    const int blocksPerRow = columns / 256;
    std::vector<float> output(static_cast<std::size_t>(rows));

    std::size_t blockOffset = 0U;
    for (jint row = 0; row < rows; ++row) {
        double sum = 0.0;
        for (int block = 0; block < blocksPerRow; ++block) {
            const float d = readHalf(matrix + blockOffset);
            const float dmin = readHalf(matrix + blockOffset + 2U);
            const std::uint8_t* scales = matrix + blockOffset + 4U;
            const std::uint8_t* qs = matrix + blockOffset + 16U;
            const int vectorBlockBase = block * 256;

            int scaleIndex = 0;
            for (int group = 0; group < 4; ++group) {
                const auto first = scaleMinK4(scales, scaleIndex);
                const auto second = scaleMinK4(scales, scaleIndex + 1);
                const float d1 = d * static_cast<float>(first.first);
                const float m1 = dmin * static_cast<float>(first.second);
                const float d2 = d * static_cast<float>(second.first);
                const float m2 = dmin * static_cast<float>(second.second);
                const std::uint8_t* packed = qs + group * 32;
                const int groupBase = vectorBlockBase + group * 64;

                for (int l = 0; l < 32; ++l) {
                    const std::uint8_t q = packed[l];
                    const float w1 =
                        d1 * static_cast<float>(q & 0x0fU) - m1;
                    const float w2 =
                        d2 * static_cast<float>(q >> 4U) - m2;
                    sum += static_cast<double>(w1) *
                        static_cast<double>(vector[groupBase + l]);
                    sum += static_cast<double>(w2) *
                        static_cast<double>(vector[groupBase + 32 + l]);
                }
                scaleIndex += 2;
            }
            blockOffset += 144U;
        }
        output[static_cast<std::size_t>(row)] = static_cast<float>(sum);
    }

    env->ReleaseByteArrayElements(matrixArray, matrixRaw, JNI_ABORT);
    env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeMatVecQ5K(
    JNIEnv* env,
    jobject,
    jbyteArray matrixArray,
    jint rows,
    jint columns,
    jfloatArray vectorArray
) {
    if (!validateKQuantShape(
            env, matrixArray, rows, columns, vectorArray, 256, 176,
            "AMNE Q5_K matvec arguments are invalid")) {
        return nullptr;
    }

    jbyte* matrixRaw = env->GetByteArrayElements(matrixArray, nullptr);
    jfloat* vector = env->GetFloatArrayElements(vectorArray, nullptr);
    if (matrixRaw == nullptr || vector == nullptr) {
        if (matrixRaw != nullptr) env->ReleaseByteArrayElements(matrixArray, matrixRaw, JNI_ABORT);
        if (vector != nullptr) env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
        return nullptr;
    }

    const auto* matrix = reinterpret_cast<const std::uint8_t*>(matrixRaw);
    const int blocksPerRow = columns / 256;
    std::vector<float> output(static_cast<std::size_t>(rows));

    std::size_t blockOffset = 0U;
    for (jint row = 0; row < rows; ++row) {
        double sum = 0.0;
        for (int block = 0; block < blocksPerRow; ++block) {
            const float d = readHalf(matrix + blockOffset);
            const float dmin = readHalf(matrix + blockOffset + 2U);
            const std::uint8_t* scales = matrix + blockOffset + 4U;
            const std::uint8_t* qh = matrix + blockOffset + 16U;
            const std::uint8_t* ql = matrix + blockOffset + 48U;
            const int vectorBlockBase = block * 256;

            int scaleIndex = 0;
            int highMask1 = 1;
            int highMask2 = 2;
            for (int group = 0; group < 4; ++group) {
                const auto first = scaleMinK4(scales, scaleIndex);
                const auto second = scaleMinK4(scales, scaleIndex + 1);
                const float d1 = d * static_cast<float>(first.first);
                const float m1 = dmin * static_cast<float>(first.second);
                const float d2 = d * static_cast<float>(second.first);
                const float m2 = dmin * static_cast<float>(second.second);
                const std::uint8_t* packed = ql + group * 32;
                const int groupBase = vectorBlockBase + group * 64;

                for (int l = 0; l < 32; ++l) {
                    const std::uint8_t low = packed[l];
                    const std::uint8_t high = qh[l];
                    const int q1 =
                        static_cast<int>(low & 0x0fU) +
                        ((high & static_cast<std::uint8_t>(highMask1)) != 0U ? 16 : 0);
                    const int q2 =
                        static_cast<int>(low >> 4U) +
                        ((high & static_cast<std::uint8_t>(highMask2)) != 0U ? 16 : 0);
                    const float w1 = d1 * static_cast<float>(q1) - m1;
                    const float w2 = d2 * static_cast<float>(q2) - m2;
                    sum += static_cast<double>(w1) *
                        static_cast<double>(vector[groupBase + l]);
                    sum += static_cast<double>(w2) *
                        static_cast<double>(vector[groupBase + 32 + l]);
                }

                scaleIndex += 2;
                highMask1 <<= 2;
                highMask2 <<= 2;
            }
            blockOffset += 176U;
        }
        output[static_cast<std::size_t>(row)] = static_cast<float>(sum);
    }

    env->ReleaseByteArrayElements(matrixArray, matrixRaw, JNI_ABORT);
    env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeMatVecQ6K(
    JNIEnv* env,
    jobject,
    jbyteArray matrixArray,
    jint rows,
    jint columns,
    jfloatArray vectorArray
) {
    if (!validateKQuantShape(
            env, matrixArray, rows, columns, vectorArray, 256, 210,
            "AMNE Q6_K matvec arguments are invalid")) {
        return nullptr;
    }

    jbyte* matrixRaw = env->GetByteArrayElements(matrixArray, nullptr);
    jfloat* vector = env->GetFloatArrayElements(vectorArray, nullptr);
    if (matrixRaw == nullptr || vector == nullptr) {
        if (matrixRaw != nullptr) env->ReleaseByteArrayElements(matrixArray, matrixRaw, JNI_ABORT);
        if (vector != nullptr) env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
        return nullptr;
    }

    const auto* matrix = reinterpret_cast<const std::uint8_t*>(matrixRaw);
    const int blocksPerRow = columns / 256;
    std::vector<float> output(static_cast<std::size_t>(rows));

    std::size_t blockOffset = 0U;
    for (jint row = 0; row < rows; ++row) {
        double sum = 0.0;
        for (int block = 0; block < blocksPerRow; ++block) {
            const std::uint8_t* qlBase = matrix + blockOffset;
            const std::uint8_t* qhBase = matrix + blockOffset + 128U;
            const auto* scales =
                reinterpret_cast<const std::int8_t*>(matrix + blockOffset + 192U);
            const float d = readHalf(matrix + blockOffset + 208U);
            const int vectorBlockBase = block * 256;

            for (int half = 0; half < 2; ++half) {
                const std::uint8_t* ql = qlBase + half * 64;
                const std::uint8_t* qh = qhBase + half * 32;
                const std::int8_t* sc = scales + half * 8;
                const int outBase = vectorBlockBase + half * 128;

                for (int l = 0; l < 32; ++l) {
                    const int iscale = l / 16;
                    const std::uint8_t low0 = ql[l];
                    const std::uint8_t low1 = ql[32 + l];
                    const std::uint8_t high = qh[l];

                    const int q1 =
                        static_cast<int>(
                            (low0 & 0x0fU) |
                            (((high >> 0U) & 3U) << 4U)
                        ) - 32;
                    const int q2 =
                        static_cast<int>(
                            (low1 & 0x0fU) |
                            (((high >> 2U) & 3U) << 4U)
                        ) - 32;
                    const int q3 =
                        static_cast<int>(
                            (low0 >> 4U) |
                            (((high >> 4U) & 3U) << 4U)
                        ) - 32;
                    const int q4 =
                        static_cast<int>(
                            (low1 >> 4U) |
                            (((high >> 6U) & 3U) << 4U)
                        ) - 32;

                    const float w1 =
                        d * static_cast<float>(sc[iscale]) * static_cast<float>(q1);
                    const float w2 =
                        d * static_cast<float>(sc[iscale + 2]) * static_cast<float>(q2);
                    const float w3 =
                        d * static_cast<float>(sc[iscale + 4]) * static_cast<float>(q3);
                    const float w4 =
                        d * static_cast<float>(sc[iscale + 6]) * static_cast<float>(q4);

                    sum += static_cast<double>(w1) *
                        static_cast<double>(vector[outBase + l]);
                    sum += static_cast<double>(w2) *
                        static_cast<double>(vector[outBase + 32 + l]);
                    sum += static_cast<double>(w3) *
                        static_cast<double>(vector[outBase + 64 + l]);
                    sum += static_cast<double>(w4) *
                        static_cast<double>(vector[outBase + 96 + l]);
                }
            }
            blockOffset += 210U;
        }
        output[static_cast<std::size_t>(row)] = static_cast<float>(sum);
    }

    env->ReleaseByteArrayElements(matrixArray, matrixRaw, JNI_ABORT);
    env->ReleaseFloatArrayElements(vectorArray, vector, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeRmsNormF32(
    JNIEnv* env,
    jobject,
    jfloatArray inputArray,
    jfloatArray weightArray,
    jfloat epsilon
) {
    if (inputArray == nullptr || weightArray == nullptr || !(epsilon > 0.0f)) {
        throwIllegalArgument(env, "AMNE RMSNorm arguments are invalid");
        return nullptr;
    }
    const jsize size = env->GetArrayLength(inputArray);
    if (size <= 0 || env->GetArrayLength(weightArray) != size) {
        throwIllegalArgument(env, "AMNE RMSNorm shape mismatch");
        return nullptr;
    }

    jfloat* input = env->GetFloatArrayElements(inputArray, nullptr);
    jfloat* weight = env->GetFloatArrayElements(weightArray, nullptr);
    if (input == nullptr || weight == nullptr) {
        if (input != nullptr) env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);
        if (weight != nullptr) env->ReleaseFloatArrayElements(weightArray, weight, JNI_ABORT);
        return nullptr;
    }

    double sumSquares = 0.0;
    for (jsize i = 0; i < size; ++i) {
        const double value = static_cast<double>(input[i]);
        sumSquares += value * value;
    }
    const double inverseRms =
        1.0 / std::sqrt(sumSquares / static_cast<double>(size) + epsilon);

    std::vector<float> output(static_cast<std::size_t>(size));
    for (jsize i = 0; i < size; ++i) {
        output[static_cast<std::size_t>(i)] =
            static_cast<float>(
                static_cast<double>(input[i]) * inverseRms *
                static_cast<double>(weight[i])
            );
    }

    env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);
    env->ReleaseFloatArrayElements(weightArray, weight, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeSiluF32(
    JNIEnv* env,
    jobject,
    jfloatArray inputArray
) {
    if (inputArray == nullptr) {
        throwIllegalArgument(env, "AMNE SiLU input must not be null");
        return nullptr;
    }
    const jsize size = env->GetArrayLength(inputArray);
    jfloat* input = env->GetFloatArrayElements(inputArray, nullptr);
    if (input == nullptr) return nullptr;

    std::vector<float> output(static_cast<std::size_t>(size));
    for (jsize i = 0; i < size; ++i) {
        const double x = static_cast<double>(input[i]);
        output[static_cast<std::size_t>(i)] =
            static_cast<float>(x / (1.0 + std::exp(-x)));
    }

    env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeSwiGluF32(
    JNIEnv* env,
    jobject,
    jfloatArray gateArray,
    jfloatArray upArray
) {
    if (gateArray == nullptr || upArray == nullptr) {
        throwIllegalArgument(env, "AMNE SwiGLU arrays must not be null");
        return nullptr;
    }
    const jsize size = env->GetArrayLength(gateArray);
    if (env->GetArrayLength(upArray) != size) {
        throwIllegalArgument(env, "AMNE SwiGLU shape mismatch");
        return nullptr;
    }

    jfloat* gate = env->GetFloatArrayElements(gateArray, nullptr);
    jfloat* up = env->GetFloatArrayElements(upArray, nullptr);
    if (gate == nullptr || up == nullptr) {
        if (gate != nullptr) env->ReleaseFloatArrayElements(gateArray, gate, JNI_ABORT);
        if (up != nullptr) env->ReleaseFloatArrayElements(upArray, up, JNI_ABORT);
        return nullptr;
    }

    std::vector<float> output(static_cast<std::size_t>(size));
    for (jsize i = 0; i < size; ++i) {
        const double x = static_cast<double>(gate[i]);
        const double silu = x / (1.0 + std::exp(-x));
        output[static_cast<std::size_t>(i)] =
            static_cast<float>(silu * static_cast<double>(up[i]));
    }

    env->ReleaseFloatArrayElements(gateArray, gate, JNI_ABORT);
    env->ReleaseFloatArrayElements(upArray, up, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeSoftmaxF32(
    JNIEnv* env,
    jobject,
    jfloatArray inputArray
) {
    if (inputArray == nullptr) {
        throwIllegalArgument(env, "AMNE softmax input must not be null");
        return nullptr;
    }
    const jsize size = env->GetArrayLength(inputArray);
    if (size <= 0) {
        throwIllegalArgument(env, "AMNE softmax input must not be empty");
        return nullptr;
    }

    jfloat* input = env->GetFloatArrayElements(inputArray, nullptr);
    if (input == nullptr) return nullptr;
    if (!requireFiniteVector(input, size)) {
        env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);
        throwIllegalArgument(env, "AMNE softmax input must be finite");
        return nullptr;
    }

    float maxValue = input[0];
    for (jsize i = 1; i < size; ++i) {
        maxValue = std::max(maxValue, input[i]);
    }

    std::vector<float> output(static_cast<std::size_t>(size));
    double denominator = 0.0;
    for (jsize i = 0; i < size; ++i) {
        const double value =
            std::exp(static_cast<double>(input[i] - maxValue));
        output[static_cast<std::size_t>(i)] = static_cast<float>(value);
        denominator += value;
    }
    if (!(denominator > 0.0) || !std::isfinite(denominator)) {
        env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);
        throwIllegalArgument(env, "AMNE softmax denominator is invalid");
        return nullptr;
    }
    for (float& value : output) {
        value = static_cast<float>(static_cast<double>(value) / denominator);
    }

    env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeRopeF32(
    JNIEnv* env,
    jobject,
    jfloatArray inputArray,
    jint position,
    jfloat theta
) {
    if (inputArray == nullptr || position < 0 || !(theta > 1.0f)) {
        throwIllegalArgument(env, "AMNE RoPE arguments are invalid");
        return nullptr;
    }
    const jsize size = env->GetArrayLength(inputArray);
    if (size <= 0 || (size % 2) != 0) {
        throwIllegalArgument(env, "AMNE RoPE width must be positive and even");
        return nullptr;
    }

    jfloat* input = env->GetFloatArrayElements(inputArray, nullptr);
    if (input == nullptr) return nullptr;

    std::vector<float> output(
        input,
        input + static_cast<std::ptrdiff_t>(size)
    );
    const int pairs = size / 2;
    for (int pair = 0; pair < pairs; ++pair) {
        const int even = pair * 2;
        const int odd = even + 1;
        const double exponent =
            static_cast<double>(even) / static_cast<double>(size);
        const double frequency =
            1.0 / std::pow(static_cast<double>(theta), exponent);
        const double angle =
            static_cast<double>(position) * frequency;
        const double c = std::cos(angle);
        const double s = std::sin(angle);
        const double x0 = static_cast<double>(input[even]);
        const double x1 = static_cast<double>(input[odd]);
        output[static_cast<std::size_t>(even)] =
            static_cast<float>(x0 * c - x1 * s);
        output[static_cast<std::size_t>(odd)] =
            static_cast<float>(x0 * s + x1 * c);
    }

    env->ReleaseFloatArrayElements(inputArray, input, JNI_ABORT);
    return makeFloatArray(env, output);
}

extern "C"
JNIEXPORT jint JNICALL
Java_io_amper_neuroos_core_AmneNativeNeonKernels_nativeAbiVersion(
    JNIEnv*,
    jobject
) {
    return 3;
}

JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "AMNE ARM64/NEON runtime loaded");
    return JNI_VERSION_1_6;
}
