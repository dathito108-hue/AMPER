#pragma once

#include <cstddef>
#include <cstdint>

namespace amne {

constexpr int kAbiVersion = 1;
constexpr int kQ4_0BlockSize = 32;
constexpr int kQ4_0BlockBytes = 18;
constexpr int kQ8_0BlockSize = 32;
constexpr int kQ8_0BlockBytes = 34;

float half_to_float(std::uint16_t bits) noexcept;

double dot_f32(const float* left, const float* right, std::size_t count) noexcept;

bool matvec_f32(
    const float* matrix,
    std::size_t matrix_count,
    int rows,
    int columns,
    const float* vector,
    std::size_t vector_count,
    float* output,
    std::size_t output_count
) noexcept;

bool matvec_q4_0(
    const std::uint8_t* matrix,
    std::size_t matrix_bytes,
    int rows,
    int columns,
    const float* vector,
    std::size_t vector_count,
    float* output,
    std::size_t output_count
) noexcept;

bool matvec_q8_0(
    const std::int8_t* matrix,
    std::size_t matrix_bytes,
    int rows,
    int columns,
    const float* vector,
    std::size_t vector_count,
    float* output,
    std::size_t output_count
) noexcept;

bool rms_norm_f32(
    const float* input,
    const float* weight,
    std::size_t count,
    float epsilon,
    float* output
) noexcept;

bool silu_f32(
    const float* input,
    std::size_t count,
    float* output
) noexcept;

bool swiglu_f32(
    const float* gate,
    const float* up,
    std::size_t count,
    float* output
) noexcept;

bool softmax_f32(
    const float* input,
    std::size_t count,
    float* output
) noexcept;

bool rope_f32(
    const float* input,
    std::size_t count,
    int position,
    float theta,
    float* output
) noexcept;

}  // namespace amne
