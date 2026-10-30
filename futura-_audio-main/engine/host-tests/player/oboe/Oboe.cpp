#include "Oboe.h"
namespace oboe {
std::vector<float>& capturedFrames() { static std::vector<float> v; return v; }
std::mutex& capturedMutex() { static std::mutex m; return m; }
}  // namespace oboe
