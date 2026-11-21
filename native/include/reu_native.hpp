#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace reu {

struct ElfSectionSummary {
    std::string name;
    uint32_t type = 0;
    uint64_t address = 0;
    uint64_t offset = 0;
    uint64_t size = 0;
};

struct ElfInspectionResult {
    bool valid = false;
    bool is64Bit = false;
    bool isLittleEndian = true;
    uint16_t machine = 0;
    std::string archName;
    uint64_t entryPoint = 0;
    std::vector<ElfSectionSummary> sections;
    std::vector<std::string> exportedSymbols;
    std::string errorMessage;
};

struct Il2CppMetadataSummary {
    bool valid = false;
    uint32_t sanity = 0;
    int32_t version = 0;
    uint32_t stringLiteralCount = 0;
    uint32_t typeDefinitionCount = 0;
    uint32_t methodCount = 0;
    uint32_t fieldCount = 0;
    uint32_t imageCount = 0;
    std::string errorMessage;
};

struct UnityFsHeaderSummary {
    bool valid = false;
    std::string signature;
    uint32_t formatVersion = 0;
    std::string unityWebVersion;
    std::string unityRevision;
    int64_t bundleSize = 0;
    uint32_t compressedBlocksSize = 0;
    uint32_t uncompressedBlocksSize = 0;
    uint32_t flags = 0;
    std::string errorMessage;
};

ElfInspectionResult inspectElfFile(const std::string& filePath, std::size_t maxSymbols = 4096);
Il2CppMetadataSummary inspectIl2CppMetadataFile(const std::string& filePath);
UnityFsHeaderSummary inspectUnityFsFile(const std::string& filePath);

} // namespace reu
