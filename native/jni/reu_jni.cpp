#include <jni.h>
#include "reu_native.hpp"

#include <sstream>
#include <string>

namespace {

std::string jstringToStd(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::string escapeJson(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_reu_integrations_NativeEngineBridge_nativeEngineInfo(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("REU C++20 Native Binary Engine 1.0.0 (ELF + IL2CPP + UnityFS)");
}

JNIEXPORT jstring JNICALL
Java_com_reu_integrations_NativeEngineBridge_nativeInspectElfJson(JNIEnv* env, jobject /*thiz*/, jstring path) {
    const auto res = reu::inspectElfFile(jstringToStd(env, path));
    std::ostringstream oss;
    oss << "{\"valid\":" << (res.valid ? "true" : "false")
        << ",\"is64Bit\":" << (res.is64Bit ? "true" : "false")
        << ",\"archName\":\"" << escapeJson(res.archName) << "\""
        << ",\"sectionCount\":" << res.sections.size()
        << ",\"symbolCount\":" << res.exportedSymbols.size()
        << ",\"error\":\"" << escapeJson(res.errorMessage) << "\"}";
    return env->NewStringUTF(oss.str().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_reu_integrations_NativeEngineBridge_nativeInspectIl2CppMetadataJson(JNIEnv* env, jobject /*thiz*/, jstring path) {
    const auto res = reu::inspectIl2CppMetadataFile(jstringToStd(env, path));
    std::ostringstream oss;
    oss << "{\"valid\":" << (res.valid ? "true" : "false")
        << ",\"version\":" << res.version
        << ",\"stringLiteralCount\":" << res.stringLiteralCount
        << ",\"typeDefinitionCount\":" << res.typeDefinitionCount
        << ",\"methodCount\":" << res.methodCount
        << ",\"error\":\"" << escapeJson(res.errorMessage) << "\"}";
    return env->NewStringUTF(oss.str().c_str());
}

} // extern "C"
