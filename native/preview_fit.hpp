#pragma once
#include "core.hpp"
#include <array>
namespace sl {
// Version 1: bounded matrix + shared monotone curve, matrix-before-curve model.
struct PreviewFit {
    std::array<int,9> matrix;
    std::array<int,1024> gamma;
    double mae=0,p95=0,identityMae=0,elapsed=0;
    std::string profile(const std::string& digest) const;
};
PreviewFit fit_preview(const Lut& lut);
}
