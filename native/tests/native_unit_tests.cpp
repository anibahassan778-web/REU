#include "reu_native.hpp"

#include <cassert>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <vector>

namespace {

void writeMinimalElf64(const std::filesystem::path& path) {
    std::vector<uint8_t> elf(64, 0);
    elf[0] = 0x7F; elf[1] = 'E'; elf[2] = 'L'; elf[3] = 'F';
    elf[4] = 2; // 64-bit
    elf[5] = 1; // little endian
    elf[18] = 0xB7; // EM_AARCH64
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(elf.data()), static_cast<std::streamsize>(elf.size()));
}

void writeMinimalIl2CppMetadata(const std::filesystem::path& path) {
    std::vector<uint8_t> meta(256, 0);
    // 0xFAB11BAF little-endian
    meta[0] = 0xAF; meta[1] = 0x1B; meta[2] = 0xB1; meta[3] = 0xFA;
    // version 29
    meta[4] = 29;
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(meta.data()), static_cast<std::streamsize>(meta.size()));
}

void writeMinimalUnityFs(const std::filesystem::path& path) {
    std::vector<uint8_t> data;
    const std::string sig = "UnityFS";
    data.insert(data.end(), sig.begin(), sig.end());
    data.push_back(0);
    // format version = 7 (BE)
    data.push_back(0); data.push_back(0); data.push_back(0); data.push_back(7);
    const std::string web = "5.x.x";
    data.insert(data.end(), web.begin(), web.end());
    data.push_back(0);
    const std::string rev = "2022.3.20f1";
    data.insert(data.end(), rev.begin(), rev.end());
    data.push_back(0);
    data.resize(data.size() + 20, 0);
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
}

} // namespace

int main() {
    const auto tmpDir = std::filesystem::temp_directory_path() / "reu_native_test";
    std::filesystem::create_directories(tmpDir);

    const auto elfPath = tmpDir / "libil2cpp.so";
    writeMinimalElf64(elfPath);
    const auto elfRes = reu::inspectElfFile(elfPath.string());
    assert(elfRes.valid);
    assert(elfRes.is64Bit);
    assert(elfRes.machine == 0xB7);

    const auto metaPath = tmpDir / "global-metadata.dat";
    writeMinimalIl2CppMetadata(metaPath);
    const auto metaRes = reu::inspectIl2CppMetadataFile(metaPath.string());
    assert(metaRes.valid);
    assert(metaRes.version == 29);

    const auto bundlePath = tmpDir / "test.bundle";
    writeMinimalUnityFs(bundlePath);
    const auto bundleRes = reu::inspectUnityFsFile(bundlePath.string());
    assert(bundleRes.valid);
    assert(bundleRes.formatVersion == 7);
    assert(bundleRes.unityRevision == "2022.3.20f1");

    std::filesystem::remove_all(tmpDir);
    return 0;
}
