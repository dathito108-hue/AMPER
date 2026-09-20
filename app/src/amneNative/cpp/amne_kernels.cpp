#include "amne_kernels.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace amne {

float half_to_float(std::uint16_t bits) noexcept {
    const std::uint32_t sign = (bits >> 15u) & 0x1u;
    const std::uint32_t exponent = (bits >> 10u) & 0x1fu;
    const std::uint32_t fraction = bits & 0x3ffu;

    std::uint32_t float_bits = 0u;
    if (exponent == 0u && fraction == 0u) {
        float_bits = sign << 31u;
    } else if (exponent == 0u) {
        std::uint32_t mantissa = fraction;
        int shift = 0;
        while ((mantissa & 0x400u) == 0u) {
            mantissa <<= 1u;
            ++shift;
        }
        mantissa &= 0x3ffu;
        const std::uint32_t adjusted_exponent =
            static_cast<std::uint32_t>(127 - 15 - shift + 1);
        float_bits =
            (sign << 31u) |
            (adjusted_exponent << 23u) |
            (mantissa << 13u);
    } else if (exponent == 0x1fu) {
        float_bits =
            (sign << 31u) |
            0x7f800000u |
            (fraction << 13u);
    } else {
        const std::uint32_t adjusted_exponent = exponent - 15u + 127u;
        float_bits =
            (sign << 31u) |
            (adjusted_exponent << 23u) |
            (fraction << 13u);
    }

    float output = 0.0f;
    static_assert(sizeof(output) == sizeof(float_bits));
    std::memcpy(&output, &float_bits, sizeof(output));
    return output;
}

double dot_f32(const float* left, const float* right, std::size_t count) noexcept {
    if (left == nullptr || right == nullptr) {
        return std::numeric_limits<double>::quiet_NaN();
    }
    double sum = 0.0;
    for (std::size_t index = 0; index < count; ++index) {
        sum += static_cast<double>(left[index]) * static_cast<double>(right[index]);
    }
    return sum;
}

bool matvec_f32(
    const float* matrix,
    std::size_t matrix_count,
    int rows,
    int columns,
    const float* vector,
    std::size_t vector_count,
    float* output,
    std::size_t output_count
) noexcept {
    if (matrix == nullptr || vector == nullptr || output == nullptr ||
        rows <= 0 || columns <= 0 ||
        vector_count != static_cast<std::size_t>(columns) ||
        output_count != static_cast<std::size_t>(rows)) {
        return false;
    }
    const std::size_t required =
        static_cast<std::size_t>(rows) * static_cast<std::size_t>(columns);
    if (required / static_cast<std::size_t>(columns) != static_cast<std::size_t>(rows) ||
        matrix_count != required) {
        return false;
    }

    for (int row = 0; row < rows; ++row) {
        const float* row_ptr =
            matrix + static_cast<std::size_t>(row) * static_cast<std::size_t>(columns);
        output[row] = static_cast<float>(
            dot_f32(row_ptr, vector, static_cast<std::size_t>(columns))
        );
    }
    return true;
}

bool matvec_q4_0(
    const std::uint8_t* matrix,
    std::size_t matrix_bytes,
    int rows,
    int columns,
    const float* vector,
    std::size_t vector_count,
    float* output,
    std::size_t output_count
) noexcept {
    if (matrix == nullptr || vector == nullptr || output == nullptr ||
        rows <= 0 || columns <= 0 ||
        columns % kQ4_0BlockSize != 0 ||
        vector_count != static_cast<std::size_t>(columns) ||
        output_count != static_cast<std::size_t>(rows)) {
        return false;
    }

    const std::size_t blocks_per_row =
        static_cast<std::size_t>(columns / kQ4_0BlockSize);
    const std::size_t required =
        static_cast<std::size_t>(rows) * blocks_per_row *
        static_cast<std::size_t>(kQ4_0BlockBytes);
    if (matrix_bytes != required) {
        return false;
    }

    std::size_t byte_offset = 0;
    for (int row = 0; row < rows; ++row) {
        double sum = 0.0;
        for (std::size_t block = 0; block < blocks_per_row; ++block) {
            const std::uint16_t scale_bits =
                static_cast<std::uint16_t>(matrix[byte_offset]) |
                (static_cast<std::uint16_t>(matrix[byte_offset + 1]) << 8u);
            const float scale = half_to_float(scale_bits);
            const std::size_t vector_base = block * kQ4_0BlockSize;

            for (int packed_index = 0; packed_index < 16; ++packed_index) {
                const std::uint8_t packed =
                    matrix[byte_offset + 2 + static_cast<std::size_t>(packed_index)];
                const int low = static_cast<int>(packed & 0x0fu) - 8;
                const int high = static_cast<int>((packed >> 4u) & 0x0fu) - 8;

                sum +=
                    static_cast<double>(scale * static_cast<float>(low)) *
                    static_cast<double>(vector[vector_base + packed_index]);
                sum +=
                    static_cast<double>(scale * static_cast<float>(high)) *
                    static_cast<double>(vector[vector_base + 16 + packed_index]);
            }
            byte_offset += kQ4_0BlockBytes;
        }
        output[row] = static_cast<float>(sum);
    }
    return true;
}

bool matvec_q8_0(
    const std::int8_t* matrix,
    std::size_t matrix_bytes,
    int rows,
    int columns,
    const float* vector,
    std::size_t vector_count,
    float* output,
    std::size_t output_count
) noexcept {
    if (matrix == nullptr || vector == nullptr || output == nullptr ||
        rows <= 0 || columns <= 0 ||
        columns % kQ8_0BlockSize != 0 ||
        vector_count != static_cast<std::size_t>(columns) ||
        output_count != static_cast<std::size_t>(rows)) {
        return false;
    }

    const std::size_t blocks_per_row =
        static_cast<std::size_t>(columns / kQ8_0BlockSize);
    const std::size_t required =
        static_cast<std::size_t>(rows) * blocks_per_row *
        static_cast<std::size_t>(kQ8_0BlockBytes);
    if (matrix_bytes != required) {
        return false;
    }

    std::size_t byte_offset = 0;
    for (int row = 0; row < rows; ++row) {
        double sum = 0.0;
        for (std::size_t block = 0; block < blocks_per_row; ++block) {
            const auto b0 = static_cast<std::uint8_t>(matrix[byte_offset]);
            const auto b1 = static_cast<std::uint8_t>(matrix[byte_offset + 1]);
            const std::uint16_t scale_bits =
                static_cast<std::uint16_t>(b0) |
                (static_cast<std::uint16_t>(b1) << 8u);
            const float scale = half_to_float(scale_bits);
            const std::size_t vector_base = block * kQ8_0BlockSize;

            for (int element = 0; element < 32; ++element) {
                const int quantized = static_cast<int>(
                    matrix[byte_offset + 2 + static_cast<std::size_t>(element)]
                );
                sum +=
                    static_cast<double>(scale * static_cast<float>(quantized)) *
                    static_cast<double>(vector[vector_base + element]);
            }
            byte_offset += kQ8_0BlockBytes;
        }
        output[row] = static_cast<float>(sum);
    }
    return true;
}

bool rms_norm_f32(
    const float* input,
    const float* weight,
    std::size_t count,
    float epsilon,
    float* output
) noexcept {
    if (input == nullptr || weight == nullptr || output == nullptr ||
        count == 0 || !(epsilon > 0.0f) || !std::isfinite(epsilon)) {
        return false;
    }

    double sum_squares = 0.0;
    for (std::size_t index = 0; index < count; ++index) {
        const double value = static_cast<double>(input[index]);
        sum_squares += value * value;
    }
    const double inverse_rms =
        1.0 / std::sqrt(sum_squares / static_cast<double>(count) +
                        static_cast<double>(epsilon));

    for (std::size_t index = 0; index < count; ++index) {
        output[index] = static_cast<float>(
            static_cast<double>(input[index]) *
            inverse_rms *
            static_cast<double>(weight[index])
        );
    }
    return true;
}

bool silu_f32(
    const float* input,
    std::size_t count,
    float* output
) noexcept {
    if (input == nullptr || output == nullptr) {
        return false;
    }
    for (std::size_t index = 0; index < count; ++index) {
        const double value = static_cast<double>(input[index]);
        output[index] = static_cast<float>(value / (1.0 + std::exp(-value)));
    }
    return true;
}

bool swiglu_f32(
    const float* gate,
    const float* up,
    std::size_t count,
    float* output
) noexcept {
    if (gate == nullptr || up == nullptr || output == nullptr) {
        return false;
    }
    for (std::size_t index = 0; index < count; ++index) {
        const double gate_value = static_cast<double>(gate[index]);
        const double silu = gate_value / (1.0 + std::exp(-gate_value));
        output[index] = static_cast<float>(silu * static_cast<double>(up[index]));
    }
    return true;
}

bool softmax_f32(
    const float* input,
    std::size_t count,
    float* output
) noexcept {
    if (input == nullptr || output == nullptr || count == 0) {
        return false;
    }

    double maximum = static_cast<double>(input[0]);
    for (std::size_t index = 1; index < count; ++index) {
        maximum = std::max(maximum, static_cast<double>(input[index]));
    }

    double denominator = 0.0;
    for (std::size_t index = 0; index < count; ++index) {
        const double value = std::exp(static_cast<double>(input[index]) - maximum);
        output[index] = static_cast<float>(value);
        denominator += value;
    }

    if (!(denominator > 0.0) || !std::isfinite(denominator)) {
        return false;
    }

    for (std::size_t index = 0; index < count; ++index) {
        output[index] = static_cast<float>(
            static_cast<double>(output[index]) / denominator
        );
    }
    return true;
}

bool rope_f32(
    const float* input,
    std::size_t count,
    int position,
    float theta,
    float* output
) noexcept {
    if (input == nullptr || output == nullptr ||
        count == 0 || count % 2 != 0 ||
        position < 0 || !(theta > 1.0f) || !std::isfinite(theta)) {
        return false;
    }

    for (std::size_t pair = 0; pair < count / 2; ++pair) {
        const std::size_t even = pair * 2;
        const std::size_t odd = even + 1;
        const double exponent =
            static_cast<double>(even) / static_cast<double>(count);
        const double frequency =
            1.0 / std::pow(static_cast<double>(theta), exponent);
        const double angle = static_cast<double>(position) * frequency;
        const double c = std::cos(angle);
        const double s = std::sin(angle);
        const double x0 = static_cast<double>(input[even]);
        const double x1 = static_cast<double>(input[odd]);
        output[even] = static_cast<float>(x0 * c - x1 * s);
        output[odd] = static_cast<float>(x0 * s + x1 * c);
    }
    return true;
}

}  // namespace amne
