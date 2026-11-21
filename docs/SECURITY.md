# REU — Security & Authorized Use Architecture

## 1. Authorized Scope
REU is engineered solely for authorized reverse engineering, software preservation, debugging, and asset inspection of Unity builds owned or licensed by the user. It intentionally excludes DRM circumvention, license bypass, anti-cheat bypass, and credential extraction.

## 2. Implemented Safeguards
- **Zip-Slip / Path Traversal Defense (`PathTraversalGuard`)**: Every archive entry path is normalized and verified against the canonical destination root before any file write (`SecurityException` on `..`, null bytes, or root escape).
- **Entry & Size Bounds**: Per-entry extraction cap (`512 MB`) and max entry count (`100,000`) protect against zip bombs.
- **Original File Immutability**: Source files selected via SAF are streamed into isolated per-project workspaces; original files are never overwritten.
- **Cryptographic Integrity (`IntegrityVerifier`)**: Streaming SHA-256 digests identify duplicate imports and verify Tool Manager offline `.zip` modules.
- **Zero Dynamic Executable Code Loading (Play Integrity)**: Tool Manager blocks `.dex`, `.jar`, `.apk`, and `.so` payloads inside downloaded/imported module packs; only declarative JSON schema packs are permitted.
- **Least-Privilege Permissions**: No `READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`; all user file access uses Android Storage Access Framework (SAF).
