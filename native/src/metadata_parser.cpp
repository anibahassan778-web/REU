#include "reu_native.hpp"

#include <fstream>

namespace reu {

namespace {

uint32_t readU32LE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

int32_t readI32LE(const uint8_t* p) {
    return static_cast<int32_t>(readU32LE(p));
}

} // namespace

Il2CppMetadataSummary inspectIl2CppMetadataFile(const std::string& filePath) {
    Il2CppMetadataSummary summary;
    std::ifstream in(filePath, std::ios::binary | std::ios::ate);
    if (!in) {
        summary.errorMessage = "Unable to open global-metadata.dat: " + filePath;
        return summary;
    }

    const auto fileSize = static_cast<uint64_t>(in.tellg());
    if (fileSize < 136) {
        summary.errorMessage = "Metadata file is smaller than minimum IL2CPP header";
        return summary;
    }

    in.seekg(0, std::ios::beg);
    uint8_t header[256]{};
    const auto toRead = static_cast<std::size_t>(std::min<uint64_t>(256, fileSize));
    in.read(reinterpret_cast<char*>(header), static_cast<std::streamsize>(toRead));

    summary.sanity = readU32LE(header);
    summary.version = readI32LE(header + 4);
    if (summary.sanity != 0xFAB11BAF) {
        summary.errorMessage = "Invalid global-metadata.dat magic sanity (expected 0xFAB11BAF)";
        return summary;
    }

    if (summary.version < 16 || summary.version > 35) {
        summary.errorMessage = "Unsupported or anomalous IL2CPP metadata version: " + std::to_string(summary.version);
        return summary;
    }

    // Standard IL2CPP header layout fields (offset/size pairs in bytes):
    // 0x08 stringLiteralOffset, 0x0C stringLiteralSize (8 bytes per Il2CppStringLiteral)
    const uint32_t stringLiteralSize = readU32LE(header + 12);
    summary.stringLiteralCount = stringLiteralSize / 8;

    // For versions >= 24, typeDefinitionsCount/methodsCount/fieldsCount/imagesCount reside at known offsets;
    // compute conservatively from size/entry-size ratios when bounded within fileSize.
    const uint32_t methodsSize = readU32LE(header + 44);
    const uint32_t fieldsSize = readU32LE(header + 60);
    const uint32_t typeDefsSize = readU32LE(header + 164);
    const uint32_t imagesSize = readU32LE(header + 172);

    if (methodsSize <= fileSize) summary.methodCount = methodsSize / 32;
    if (fieldsSize <= fileSize) summary.fieldCount = fieldsSize / 12;
    if (typeDefsSize <= fileSize) summary.typeDefinitionCount = typeDefsSize / 88;
    if (imagesSize <= fileSize) summary.imageCount = imagesSize / 40;

    summary.valid = true;
    return summary;
}

} // namespace reu
