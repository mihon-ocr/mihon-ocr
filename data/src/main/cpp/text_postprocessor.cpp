#include "text_postprocessor.h"
#include <codecvt>
#include <locale>

namespace mihon {

TextPostprocessor::TextPostprocessor() {
    initializeConversionTable();
}

void TextPostprocessor::initializeConversionTable() {
    // Initialize with identity mapping
    for (size_t i = 0; i < TABLE_SIZE; i++) {
        halfToFullTable_[i] = static_cast<wchar_t>(i);
    }
    
    // Half-width to full-width mappings
    halfToFullTable_['!'] = L'！'; halfToFullTable_['"'] = L'"'; halfToFullTable_['#'] = L'＃';
    halfToFullTable_['$'] = L'＄'; halfToFullTable_['%'] = L'％'; halfToFullTable_['&'] = L'＆';
    halfToFullTable_['\''] = L'\''; halfToFullTable_['('] = L'（'; halfToFullTable_[')'] = L'）';
    halfToFullTable_['*'] = L'＊'; halfToFullTable_['+'] = L'＋'; halfToFullTable_[','] = L'，';
    halfToFullTable_['-'] = L'－'; halfToFullTable_['.'] = L'．'; halfToFullTable_['/'] = L'／';
    halfToFullTable_['0'] = L'０'; halfToFullTable_['1'] = L'１'; halfToFullTable_['2'] = L'２';
    halfToFullTable_['3'] = L'３'; halfToFullTable_['4'] = L'４'; halfToFullTable_['5'] = L'５';
    halfToFullTable_['6'] = L'６'; halfToFullTable_['7'] = L'７'; halfToFullTable_['8'] = L'８';
    halfToFullTable_['9'] = L'９'; halfToFullTable_[':'] = L'：'; halfToFullTable_[';'] = L'；';
    halfToFullTable_['<'] = L'＜'; halfToFullTable_['='] = L'＝'; halfToFullTable_['>'] = L'＞';
    halfToFullTable_['?'] = L'？'; halfToFullTable_['@'] = L'＠';
    halfToFullTable_['A'] = L'Ａ'; halfToFullTable_['B'] = L'Ｂ'; halfToFullTable_['C'] = L'Ｃ';
    halfToFullTable_['D'] = L'Ｄ'; halfToFullTable_['E'] = L'Ｅ'; halfToFullTable_['F'] = L'Ｆ';
    halfToFullTable_['G'] = L'Ｇ'; halfToFullTable_['H'] = L'Ｈ'; halfToFullTable_['I'] = L'Ｉ';
    halfToFullTable_['J'] = L'Ｊ'; halfToFullTable_['K'] = L'Ｋ'; halfToFullTable_['L'] = L'Ｌ';
    halfToFullTable_['M'] = L'Ｍ'; halfToFullTable_['N'] = L'Ｎ'; halfToFullTable_['O'] = L'Ｏ';
    halfToFullTable_['P'] = L'Ｐ'; halfToFullTable_['Q'] = L'Ｑ'; halfToFullTable_['R'] = L'Ｒ';
    halfToFullTable_['S'] = L'Ｓ'; halfToFullTable_['T'] = L'Ｔ'; halfToFullTable_['U'] = L'Ｕ';
    halfToFullTable_['V'] = L'Ｖ'; halfToFullTable_['W'] = L'Ｗ'; halfToFullTable_['X'] = L'Ｘ';
    halfToFullTable_['Y'] = L'Ｙ'; halfToFullTable_['Z'] = L'Ｚ';
    halfToFullTable_['['] = L'［'; halfToFullTable_['\\'] = L'＼'; halfToFullTable_[']'] = L'］';
    halfToFullTable_['^'] = L'＾'; halfToFullTable_['_'] = L'＿'; halfToFullTable_['`'] = L'\'';
    halfToFullTable_['a'] = L'ａ'; halfToFullTable_['b'] = L'ｂ'; halfToFullTable_['c'] = L'ｃ';
    halfToFullTable_['d'] = L'ｄ'; halfToFullTable_['e'] = L'ｅ'; halfToFullTable_['f'] = L'ｆ';
    halfToFullTable_['g'] = L'ｇ'; halfToFullTable_['h'] = L'ｈ'; halfToFullTable_['i'] = L'ｉ';
    halfToFullTable_['j'] = L'ｊ'; halfToFullTable_['k'] = L'ｋ'; halfToFullTable_['l'] = L'ｌ';
    halfToFullTable_['m'] = L'ｍ'; halfToFullTable_['n'] = L'ｎ'; halfToFullTable_['o'] = L'ｏ';
    halfToFullTable_['p'] = L'ｐ'; halfToFullTable_['q'] = L'ｑ'; halfToFullTable_['r'] = L'ｒ';
    halfToFullTable_['s'] = L'ｓ'; halfToFullTable_['t'] = L'ｔ'; halfToFullTable_['u'] = L'ｕ';
    halfToFullTable_['v'] = L'ｖ'; halfToFullTable_['w'] = L'ｗ'; halfToFullTable_['x'] = L'ｘ';
    halfToFullTable_['y'] = L'ｙ'; halfToFullTable_['z'] = L'ｚ';
    halfToFullTable_['{'] = L'｛'; halfToFullTable_['|'] = L'｜'; halfToFullTable_['}'] = L'｝';
    halfToFullTable_['~'] = L'～';
}

std::string TextPostprocessor::postprocess(const std::string& text) {
    if (text.empty()) {
        return text;
    }
    
    // Convert to wide string for Unicode processing
    std::wstring_convert<std::codecvt_utf8<wchar_t>> converter;
    std::wstring wtext = converter.from_bytes(text);
    
    std::wstring result;
    result.reserve(wtext.length());
    
    size_t i = 0;
    size_t len = wtext.length();
    
    while (i < len) {
        wchar_t c = wtext[i];
        
        // Skip whitespace
        if (std::iswspace(c)) {
            i++;
            continue;
        }
        
        // Replace ellipsis
        if (c == L'…') {
            result += L"...";
            i++;
            continue;
        }
        
        // Handle dot sequences
        if (c == L'.' || c == L'・') {
            int dotCount = 1;
            size_t laterIndex = i + 1;
            while (laterIndex < len && (wtext[laterIndex] == L'.' || wtext[laterIndex] == L'・')) {
                dotCount++;
                laterIndex++;
            }
            
            if (dotCount >= 2) {
                for (int j = 0; j < dotCount; j++) {
                    result += L'.';
                }
                i = laterIndex;
                continue;
            }
        }
        
        // Convert half-width to full-width
        if (c < TABLE_SIZE) {
            result += halfToFullTable_[c];
        } else {
            result += c;
        }
        
        i++;
    }
    
    return converter.to_bytes(result);
}

} // namespace mihon
