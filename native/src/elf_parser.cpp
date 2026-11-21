#include "reu_native.hpp"

#include <algorithm>
#include <cstring>
#include <fstream>

namespace reu {

namespace {

uint16_t readU16(const uint8_t* p, bool le) {
    return le ? static_cast<uint16_t>(p[0] | (p[1] << 8))
              : static_cast<uint16_t>((p[0] << 8) | p[1]);
}

uint32_t readU32(const uint8_t* p, bool le) {
    if (le) {
        return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
               (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
    }
    return (static_cast<uint32_t>(p[0]) << 24) | (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) | static_cast<uint32_t>(p[3]);
}

uint64_t readU64(const uint8_t* p, bool le) {
    uint64_t v = 0;
    if (le) {
        for (int i = 7; i >= 0; --i) {
            v = (v << 8) | p[i];
        }
    } else {
        for (int i = 0; i < 8; ++i) {
            v = (v << 8) | p[i];
        }
    }
    return v;
}

std::string mapMachine(uint16_t machine) {
    switch (machine) {
        case 0x03: return "x86";
        case 0x28: return "armeabi-v7a (ARM32)";
        case 0x3E: return "x86_64";
        case 0xB7: return "arm64-v8a (AArch64)";
        case 0xF3: return "RISC-V";
        default: return "Unknown (0x" + std::to_string(machine) + ")";
    }
}

std::string readCString(const std::vector<uint8_t>& buf, uint64_t offset) {
    if (offset >= buf.size()) return {};
    std::size_t end = static_cast<std::size_t>(offset);
    while (end < buf.size() && buf[end] != 0 && (end - offset) < 512) {
        ++end;
    }
    return std::string(reinterpret_cast<const char*>(buf.data() + offset), end - static_cast<std::size_t>(offset));
}

} // namespace

ElfInspectionResult inspectElfFile(const std::string& filePath, std::size_t maxSymbols) {
    ElfInspectionResult result;
    std::ifstream in(filePath, std::ios::binary | std::ios::ate);
    if (!in) {
        result.errorMessage = "Unable to open ELF file: " + filePath;
        return result;
    }

    const auto fileSize = static_cast<uint64_t>(in.tellg());
    if (fileSize < 52) {
        result.errorMessage = "File too small to be an ELF binary";
        return result;
    }

    in.seekg(0, std::ios::beg);
    uint8_t ident[64]{};
    in.read(reinterpret_cast<char*>(ident), static_cast<std::streamsize>(std::min<uint64_t>(64, fileSize)));

    if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') {
        result.errorMessage = "Invalid ELF magic bytes";
        return result;
    }

    const uint8_t elfClass = ident[4];
    const uint8_t elfData = ident[5];
    if (elfClass != 1 && elfClass != 2) {
        result.errorMessage = "Unsupported ELF class";
        return result;
    }

    result.is64Bit = (elfClass == 2);
    result.isLittleEndian = (elfData == 1);
    const bool le = result.isLittleEndian;

    if (result.is64Bit && fileSize < 64) {
        result.errorMessage = "Truncated ELF64 header";
        return result;
    }

    result.machine = readU16(ident + 18, le);
    result.archName = mapMachine(result.machine);
    result.entryPoint = result.is64Bit ? readU64(ident + 24, le) : readU32(ident + 24, le);

    const uint64_t shoff = result.is64Bit ? readU64(ident + 40, le) : readU32(ident + 32, le);
    const uint16_t shentsize = result.is64Bit ? readU16(ident + 58, le) : readU16(ident + 46, le);
    const uint16_t shnum = result.is64Bit ? readU16(ident + 60, le) : readU16(ident + 48, le);
    const uint16_t shstrndx = result.is64Bit ? readU16(ident + 62, le) : readU16(ident + 50, le);

    if (shoff == 0 || shnum == 0 || shnum > 4096 || shentsize == 0) {
        result.valid = true;
        return result;
    }

    const uint64_t tableBytes = static_cast<uint64_t>(shentsize) * shnum;
    if (shoff + tableBytes > fileSize) {
        result.errorMessage = "ELF section header table exceeds file size";
        return result;
    }

    std::vector<uint8_t> shTable(static_cast<std::size_t>(tableBytes));
    in.seekg(static_cast<std::streamoff>(shoff), std::ios::beg);
    in.read(reinterpret_cast<char*>(shTable.data()), static_cast<std::streamsize>(tableBytes));

    struct RawSec {
        uint32_t nameIdx;
        uint32_t type;
        uint64_t addr;
        uint64_t offset;
        uint64_t size;
        uint32_t link;
    };

    std::vector<RawSec> rawSections;
    rawSections.reserve(shnum);
    for (uint16_t i = 0; i < shnum; ++i) {
        const uint8_t* p = shTable.data() + static_cast<std::size_t>(i) * shentsize;
        RawSec sec{};
        sec.nameIdx = readU32(p, le);
        sec.type = readU32(p + 4, le);
        if (result.is64Bit) {
            sec.addr = readU64(p + 16, le);
            sec.offset = readU64(p + 24, le);
            sec.size = readU64(p + 32, le);
            sec.link = readU32(p + 40, le);
        } else {
            sec.addr = readU32(p + 12, le);
            sec.offset = readU32(p + 16, le);
            sec.size = readU32(p + 20, le);
            sec.link = readU32(p + 24, le);
        }
        rawSections.push_back(sec);
    }

    std::vector<uint8_t> shStrTab;
    if (shstrndx < rawSections.size()) {
        const auto& strSec = rawSections[shstrndx];
        if (strSec.offset + strSec.size <= fileSize && strSec.size <= 4 * 1024 * 1024) {
            shStrTab.resize(static_cast<std::size_t>(strSec.size));
            in.seekg(static_cast<std::streamoff>(strSec.offset), std::ios::beg);
            in.read(reinterpret_cast<char*>(shStrTab.data()), static_cast<std::streamsize>(strSec.size));
        }
    }

    for (const auto& rs : rawSections) {
        ElfSectionSummary sum;
        sum.name = readCString(shStrTab, rs.nameIdx);
        sum.type = rs.type;
        sum.address = rs.addr;
        sum.offset = rs.offset;
        sum.size = rs.size;
        result.sections.push_back(std::move(sum));

        // SHT_DYNSYM = 11, SHT_SYMTAB = 2
        if ((rs.type == 11 || rs.type == 2) && rs.link < rawSections.size() &&
            result.exportedSymbols.size() < maxSymbols) {
            const auto& strSec = rawSections[rs.link];
            if (rs.offset + rs.size <= fileSize && strSec.offset + strSec.size <= fileSize &&
                rs.size <= 16 * 1024 * 1024 && strSec.size <= 16 * 1024 * 1024) {
                std::vector<uint8_t> symBuf(static_cast<std::size_t>(rs.size));
                std::vector<uint8_t> strBuf(static_cast<std::size_t>(strSec.size));
                in.seekg(static_cast<std::streamoff>(rs.offset), std::ios::beg);
                in.read(reinterpret_cast<char*>(symBuf.data()), static_cast<std::streamsize>(rs.size));
                in.seekg(static_cast<std::streamoff>(strSec.offset), std::ios::beg);
                in.read(reinterpret_cast<char*>(strBuf.data()), static_cast<std::streamsize>(strSec.size));

                const std::size_t symEntSize = result.is64Bit ? 24 : 16;
                for (std::size_t off = 0; off + symEntSize <= symBuf.size() && result.exportedSymbols.size() < maxSymbols;
                     off += symEntSize) {
                    const uint32_t stName = readU32(symBuf.data() + off, le);
                    const uint16_t stShndx = result.is64Bit
                        ? readU16(symBuf.data() + off + 6, le)
                        : readU16(symBuf.data() + off + 14, le);
                    if (stName != 0 && stShndx != 0) {
                        std::string name = readCString(strBuf, stName);
                        if (!name.empty()) {
                            result.exportedSymbols.push_back(std::move(name));
                        }
                    }
                }
            }
        }
    }

    result.valid = true;
    return result;
}

} // namespace reu
