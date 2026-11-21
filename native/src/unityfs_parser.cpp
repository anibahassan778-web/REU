#include "reu_native.hpp"

#include <fstream>
#include <vector>

namespace reu {

namespace {

uint32_t readU32BE(const uint8_t* p) {
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

uint64_t readU64BE(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) {
        v = (v << 8) | p[i];
    }
    return v;
}

std::string readNullTerm(const std::vector<uint8_t>& buf, std::size_t& cursor) {
    const std::size_t start = cursor;
    while (cursor < buf.size() && buf[cursor] != 0) {
        ++cursor;
    }
    std::string out(reinterpret_cast<const char*>(buf.data() + start), cursor - start);
    if (cursor < buf.size()) ++cursor;
    return out;
}

} // namespace

UnityFsHeaderSummary inspectUnityFsFile(const std::string& filePath) {
    UnityFsHeaderSummary summary;
    std::ifstream in(filePath, std::ios::binary | std::ios::ate);
    if (!in) {
        summary.errorMessage = "Unable to open AssetBundle file: " + filePath;
        return summary;
    }

    const auto fileSize = static_cast<std::size_t>(in.tellg());
    if (fileSize < 32) {
        summary.errorMessage = "File too small for UnityFS header";
        return summary;
    }

    in.seekg(0, std::ios::beg);
    std::vector<uint8_t> buf(std::min<std::size_t>(512, fileSize));
    in.read(reinterpret_cast<char*>(buf.data()), static_cast<std::streamsize>(buf.size()));

    std::size_t cursor = 0;
    summary.signature = readNullTerm(buf, cursor);
    if (summary.signature != "UnityFS") {
        summary.errorMessage = "Not a UnityFS bundle (signature='" + summary.signature + "')";
        return summary;
    }

    if (cursor + 4 > buf.size()) {
        summary.errorMessage = "Truncated UnityFS version field";
        return summary;
    }
    summary.formatVersion = readU32BE(buf.data() + cursor);
    cursor += 4;

    summary.unityWebVersion = readNullTerm(buf, cursor);
    summary.unityRevision = readNullTerm(buf, cursor);

    if (cursor + 20 > buf.size()) {
        summary.errorMessage = "Truncated UnityFS bundle size/block header";
        return summary;
    }

    summary.bundleSize = static_cast<int64_t>(readU64BE(buf.data() + cursor));
    cursor += 8;
    summary.compressedBlocksSize = readU32BE(buf.data() + cursor);
    cursor += 4;
    summary.uncompressedBlocksSize = readU32BE(buf.data() + cursor);
    cursor += 4;
    summary.flags = readU32BE(buf.data() + cursor);

    summary.valid = true;
    return summary;
}

} // namespace reu
