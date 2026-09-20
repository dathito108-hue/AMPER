#include "amne_kernels.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {

void require_close(float actual, float expected, float tolerance, const char* label) {
    if (!std::isfinite(actual) || std::fabs(actual - expected) > tolerance) {
        std::cerr << label << " expected=" << expected << " actual=" << actual << "\n";
        std::exit(1);
    }
}

}  // namespace

int main() {
    {
        const std::array<float, 4> left{1.f, 2.f, 3.f, 4.f};
        const std::array<float, 4> right{2.f, 3.f, 4.f, 3.f};
        require_close(
            static_cast<float>(amne::dot_f32(left.data(), right.data(), left.size())),
            32.f,
            1e-6f,
            "dot_f32"
        );
    }

    {
        std::array<std::uint8_t, amne::kQ4_0BlockBytes> block{};
        block[0] = 0x00;
        block[1] = 0x3c;
        for (int index = 0; index < 16; ++index) {
            block[2 + index] = 0x99;
        }
        std::array<float, 32> vector{};
        vector.fill(1.f);
        std::array<float, 1> output{};
        if (!amne::matvec_q4_0(
                block.data(),
                block.size(),
                1,
                32,
                vector.data(),
                vector.size(),
                output.data(),
                output.size())) {
            return 2;
        }
        require_close(output[0], 32.f, 1e-5f, "matvec_q4_0");
    }

    {
        std::array<std::int8_t, amne::kQ8_0BlockBytes> block{};
        block[0] = 0x00;
        block[1] = 0x3c;
        for (int index = 0; index < 32; ++index) {
            block[2 + index] = static_cast<std::int8_t>(index % 2 == 0 ? 2 : -1);
        }
        std::array<float, 32> vector{};
        vector.fill(1.f);
        std::array<float, 1> output{};
        if (!amne::matvec_q8_0(
                block.data(),
                block.size(),
                1,
                32,
                vector.data(),
                vector.size(),
                output.data(),
                output.size())) {
            return 3;
        }
        require_close(output[0], 16.f, 1e-5f, "matvec_q8_0");
    }

    {
        const std::array<float, 3> logits{10000.f, 10001.f, 9999.f};
        std::array<float, 3> output{};
        if (!amne::softmax_f32(logits.data(), logits.size(), output.data())) {
            return 4;
        }
        require_close(output[0] + output[1] + output[2], 1.f, 1e-6f, "softmax_f32");
        if (!(output[1] > output[0] && output[0] > output[2])) {
            return 5;
        }
    }

    {
        const std::array<float, 4> input{1.f, -2.f, 3.f, -4.f};
        std::array<float, 4> output{};
        if (!amne::rope_f32(
                input.data(),
                input.size(),
                0,
                10000.f,
                output.data())) {
            return 6;
        }
        for (std::size_t index = 0; index < input.size(); ++index) {
            require_close(output[index], input[index], 1e-6f, "rope_f32");
        }
    }

    require_close(amne::half_to_float(0x3c00u), 1.f, 0.f, "half_to_float");
    std::cout << "AMNE native reference qualification PASS\n";
    return 0;
}
