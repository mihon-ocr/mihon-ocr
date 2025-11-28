#ifndef MIHON_TEXT_POSTPROCESSOR_H
#define MIHON_TEXT_POSTPROCESSOR_H

#include <string>
#include <array>

namespace mihon {

class TextPostprocessor {
public:
    TextPostprocessor();
    std::string postprocess(const std::string& text);

private:
    static constexpr size_t TABLE_SIZE = 127;
    std::array<wchar_t, TABLE_SIZE> halfToFullTable_;
    
    void initializeConversionTable();
};

} // namespace mihon

#endif // MIHON_TEXT_POSTPROCESSOR_H
