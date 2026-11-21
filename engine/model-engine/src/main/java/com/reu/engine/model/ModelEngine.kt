package com.reu.engine.model

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
data class MeshDataModel(
  val name: String,
  val vertices: List<Float>, // x, y, z triples
  val normals: List<Float> = emptyList(),
  val uvs: List<Float> = emptyList(),
  val indices: List<Int>, // triangle indices
)

@Serializable
data class ModelExportResult(
  val success: Boolean,
  val name: String,
  val format: String,
  val vertexCount: Int,
  val triangleCount: Int,
  val exportedPath: String?,
  val note: String,
)

class ModelEngine {
  fun exportToObj(mesh: MeshDataModel, outputDir: File): ModelExportResult {
    if (mesh.vertices.size < 9 || mesh.indices.size < 3) {
      return ModelExportResult(false, mesh.name, "OBJ", 0, 0, null, "Mesh lacks minimum vertex/triangle data.")
    }
    if (!outputDir.exists()) outputDir.mkdirs()
    val safeName = mesh.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val outFile = File(outputDir, "$safeName.obj")
    val sb = StringBuilder()
    sb.appendLine("# Exported by REU ModelEngine")
    sb.appendLine("o $safeName")

    val vCount = mesh.vertices.size / 3
    for (i in 0 until vCount) {
      sb.appendLine("v ${mesh.vertices[i * 3]} ${mesh.vertices[i * 3 + 1]} ${mesh.vertices[i * 3 + 2]}")
    }
    val uvCount = mesh.uvs.size / 2
    for (i in 0 until uvCount) {
      sb.appendLine("vt ${mesh.uvs[i * 2]} ${mesh.uvs[i * 2 + 1]}")
    }
    val nCount = mesh.normals.size / 3
    for (i in 0 until nCount) {
      sb.appendLine("vn ${mesh.normals[i * 3]} ${mesh.normals[i * 3 + 1]} ${mesh.normals[i * 3 + 2]}")
    }

    val triCount = mesh.indices.size / 3
    for (t in 0 until triCount) {
      val a = mesh.indices[t * 3] + 1
      val b = mesh.indices[t * 3 + 1] + 1
      val c = mesh.indices[t * 3 + 2] + 1
      sb.appendLine("f $a $b $c")
    }
    outFile.writeText(sb.toString())

    return ModelExportResult(
      success = true,
      name = mesh.name,
      format = "OBJ",
      vertexCount = vCount,
      triangleCount = triCount,
      exportedPath = outFile.absolutePath,
      note = "Exported Wavefront OBJ ($vCount vertices, $triCount triangles).",
    )
  }

  fun exportToGltf(mesh: MeshDataModel, outputDir: File): ModelExportResult {
    if (mesh.vertices.size < 9 || mesh.indices.size < 3) {
      return ModelExportResult(false, mesh.name, "glTF 2.0", 0, 0, null, "Mesh lacks minimum vertex/triangle data.")
    }
    if (!outputDir.exists()) outputDir.mkdirs()
    val safeName = mesh.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val outFile = File(outputDir, "$safeName.gltf")

    val vCount = mesh.vertices.size / 3
    val posByteLen = mesh.vertices.size * 4
    val idxByteLen = mesh.indices.size * 4
    val bb = ByteBuffer.allocate(posByteLen + idxByteLen).order(ByteOrder.LITTLE_ENDIAN)

    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (i in 0 until vCount) {
      val x = mesh.vertices[i * 3]
      val y = mesh.vertices[i * 3 + 1]
      val z = mesh.vertices[i * 3 + 2]
      minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
      maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
      bb.putFloat(x); bb.putFloat(y); bb.putFloat(z)
    }
    for (idx in mesh.indices) {
      bb.putInt(idx)
    }
    val b64 = Base64.getEncoder().encodeToString(bb.array())

    val gltfJson =
      """
      {
        "asset": { "version": "2.0", "generator": "REU ModelEngine" },
        "scene": 0,
        "scenes": [{ "nodes": [0] }],
        "nodes": [{ "mesh": 0, "name": "$safeName" }],
        "meshes": [{
          "name": "$safeName",
          "primitives": [{
            "attributes": { "POSITION": 0 },
            "indices": 1,
            "mode": 4
          }]
        }],
        "buffers": [{
          "uri": "data:application/octet-stream;base64,$b64",
          "byteLength": ${posByteLen + idxByteLen}
        }],
        "bufferViews": [
          { "buffer": 0, "byteOffset": 0, "byteLength": $posByteLen, "target": 34962 },
          { "buffer": 0, "byteOffset": $posByteLen, "byteLength": $idxByteLen, "target": 34963 }
        ],
        "accessors": [
          {
            "bufferView": 0,
            "componentType": 5126,
            "count": $vCount,
            "type": "VEC3",
            "min": [$minX, $minY, $minZ],
            "max": [$maxX, $maxY, $maxZ]
          },
          {
            "bufferView": 1,
            "componentType": 5125,
            "count": ${mesh.indices.size},
            "type": "SCALAR"
          }
        ]
      }
      """.trimIndent()
    outFile.writeText(gltfJson)

    return ModelExportResult(
      success = true,
      name = mesh.name,
      format = "glTF 2.0",
      vertexCount = vCount,
      triangleCount = mesh.indices.size / 3,
      exportedPath = outFile.absolutePath,
      note = "Exported embedded glTF 2.0 ($vCount vertices, ${mesh.indices.size / 3} triangles).",
    )
  }
}
