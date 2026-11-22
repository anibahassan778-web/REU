package com.reu.engine.mono

import com.reu.core.common.BinarySource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal data class CilInstruction(
  val offset: Int,
  val size: Int,
  val opcode: Int,
  val isPrefixFe: Boolean,
  val mnemonic: String,
  val intOperand: Int = 0,
  val longOperand: Long = 0L,
  val floatOperand: Float = 0f,
  val doubleOperand: Double = 0.0,
  val branchTarget: Int = -1,
  val switchTargets: IntArray = IntArray(0),
)

internal data class DecompiledMethodBody(
  val ilDisassembly: String,
  val csharpLines: List<String>,
)

internal object CilDecompiler {
  fun decompileMethod(
    src: BinarySource,
    rva: Long,
    sections: List<PeSection>,
    ctx: CliMetadataContext,
    hasThis: Boolean,
    paramNames: List<String>,
    returnType: String,
  ): DecompiledMethodBody {
    if (rva == 0L) {
      return DecompiledMethodBody(
        ilDisassembly = "// Abstract / runtime-provided method (RVA = 0x0)",
        csharpLines = listOf("// Abstract or extern runtime method (no IL body)"),
      )
    }
    val off = rvaToOffset(rva, sections)
      ?: return DecompiledMethodBody("// Unable to map RVA 0x${rva.toString(16)}", listOf("// Unable to map RVA 0x${rva.toString(16)}"))
    if (off >= src.length) {
      return DecompiledMethodBody("// RVA outside file bounds", listOf("// RVA outside file bounds"))
    }

    src.position = off
    val b0 = src.readU8()
    val codeSize: Int
    var localSigTok = 0
    if ((b0 and 0x03) == 0x02) {
      codeSize = (b0 ushr 2) and 0x3F
    } else if ((b0 and 0x03) == 0x03 && off + 12 <= src.length) {
      val headerFlags = src.readU8()
      val hdrSizeDwords = (headerFlags ushr 4) and 0x0F
      val hdrBytes = (hdrSizeDwords * 4).coerceAtLeast(12)
      src.readU16LE() // maxStack
      codeSize = src.readI32LE().coerceIn(0, 65_536)
      localSigTok = src.readI32LE()
      src.position = off + hdrBytes
    } else {
      return DecompiledMethodBody("// Unrecognized IL method header 0x${b0.toString(16)}", listOf("// Unrecognized IL method header 0x${b0.toString(16)}"))
    }

    val safeSize = minOf(codeSize.toLong(), src.length - src.position).toInt().coerceAtLeast(0)
    val ilBytes = src.readBytes(safeSize)
    val instructions = decodeInstructions(ilBytes)
    val disasm = formatDisassembly(instructions, ctx)

    val localTypes = if (localSigTok != 0) ctx.decodeLocalVarTypes(localSigTok) else emptyList()
    val csharpLines = reconstructCSharpStatements(instructions, ctx, hasThis, paramNames, localTypes, returnType)
    return DecompiledMethodBody(disasm, csharpLines)
  }

  private fun rvaToOffset(rva: Long, sections: List<PeSection>): Long? {
    for (s in sections) {
      val size = maxOf(s.virtualSize, s.rawSize)
      if (rva in s.virtualAddress until (s.virtualAddress + size)) {
        return s.rawPointer + (rva - s.virtualAddress)
      }
    }
    return null
  }

  private fun formatDisassembly(instructions: List<CilInstruction>, ctx: CliMetadataContext): String {
    if (instructions.isEmpty()) return "// Empty IL body"
    val sb = StringBuilder()
    for (ins in instructions) {
      sb.append(String.format(Locale.US, "IL_%04x: %s", ins.offset, ins.mnemonic))
      when {
        ins.branchTarget >= 0 -> sb.append(String.format(Locale.US, " IL_%04x", ins.branchTarget))
        !ins.isPrefixFe && ins.opcode == 0x22 -> sb.append(" ").append(ins.floatOperand)
        !ins.isPrefixFe && ins.opcode == 0x23 -> sb.append(" ").append(ins.doubleOperand)
        !ins.isPrefixFe && ins.opcode == 0x21 -> sb.append(" ").append(ins.longOperand)
        !ins.isPrefixFe && ins.opcode == 0x72 -> sb.append(" ").append(ctx.readUserString(ins.intOperand))
        !ins.isPrefixFe && ins.opcode in setOf(0x28, 0x29, 0x6F, 0x73) -> {
          val c = ctx.resolveCallableToken(ins.intOperand)
          sb.append(" ${c.ownerType}::${c.name}(${c.paramTypes.joinToString(", ")})")
        }
        !ins.isPrefixFe && ins.opcode in setOf(0x7B, 0x7C, 0x7D, 0x7E, 0x7F, 0x80) -> {
          val (owner, fname, _) = ctx.resolveFieldToken(ins.intOperand)
          sb.append(" $owner::$fname")
        }
        ins.size > 1 && ins.switchTargets.isEmpty() -> {
          sb.append(String.format(Locale.US, " 0x%x", ins.intOperand))
        }
      }
      sb.append('\n')
    }
    return sb.toString().trimEnd()
  }

  private data class StackVal(val code: String, val type: String = "object", val precedence: Int = 100)

  private enum class BlockKind { IF, ELSE, WHILE }
  private data class ControlBlock(
    val kind: BlockKind,
    val endOffset: Int,
    val loopStartOffset: Int = -1,
    val entryStackDepth: Int = 0,
    val headerOutIndex: Int = -1,
    val ternaryVar: String? = null,
    val ternaryType: String = "object",
  )

  private fun reconstructCSharpStatements(
    instructions: List<CilInstruction>,
    ctx: CliMetadataContext,
    hasThis: Boolean,
    paramNames: List<String>,
    localTypes: List<String>,
    returnType: String,
  ): List<String> {
    if (instructions.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val declaredLocals = mutableSetOf<Int>()
    val stack = ArrayDeque<StackVal>()
    val openBlocks = mutableListOf<ControlBlock>()
    var tempCounter = 0

    fun nextTemp(prefix: String = "tmp"): String = "$prefix${tempCounter++}"

    fun indent(extra: Int = 0): String = "    ".repeat((openBlocks.size + extra).coerceAtLeast(0))
    fun emit(line: String) {
      out.add(indent() + line)
    }

    fun formatForType(v: StackVal, expectedType: String?): String =
      if (expectedType == "bool") {
        when (v.code) {
          "0" -> "false"
          "1" -> "true"
          else -> v.code
        }
      } else {
        v.code
      }

    fun argName(idx: Int): String {
      if (hasThis) {
        if (idx == 0) return "this"
        return paramNames.getOrNull(idx - 1)?.takeIf { it.isNotBlank() } ?: "arg${idx - 1}"
      }
      return paramNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "arg$idx"
    }

    fun localName(idx: Int): String = "local$idx"

    fun popOrDefault(fallback: String = "/*stack*/ default"): StackVal =
      if (stack.isNotEmpty()) stack.removeLast() else StackVal(fallback, "object", 100)

    fun wrapIfNeeded(v: StackVal, minPrec: Int): String =
      if (v.precedence < minPrec) "(${v.code})" else v.code

    // Track unconditional forward branches at end of IF-bodies to detect ELSE blocks
    val elseJumpTargets = mutableMapOf<Int, Int>() // ifEndOffset -> elseEndOffset
    for (idx in instructions.indices) {
      val ins = instructions[idx]
      if (!ins.isPrefixFe && ins.opcode in setOf(0x2B, 0x38) && ins.branchTarget > ins.offset + ins.size) {
        val nextOff = ins.offset + ins.size
        elseJumpTargets[nextOff] = ins.branchTarget
      }
    }

    val backEdgeLoops = instructions
      .filter { !it.isPrefixFe && it.opcode in setOf(0x2B, 0x38) && it.branchTarget in 0 until it.offset }
      .associate { it.branchTarget to (it.offset + it.size) }

    var pendingConstrainedType: String? = null

    for (idx in instructions.indices) {
      val ins = instructions[idx]
      val nextOff = ins.offset + ins.size

      // Close finished blocks; convert IF->ELSE (with ternary value preservation if stack grew)
      while (openBlocks.isNotEmpty() && ins.offset >= openBlocks.last().endOffset) {
        val closed = openBlocks.removeAt(openBlocks.lastIndex)
        val elseEnd = elseJumpTargets[ins.offset]
        if (closed.kind == BlockKind.IF && elseEnd != null && elseEnd > ins.offset) {
          var ternVar: String? = null
          var ternType = "object"
          if (stack.size > closed.entryStackDepth && closed.headerOutIndex in out.indices) {
            val thenVal = stack.removeLast()
            while (stack.size > closed.entryStackDepth) stack.removeLast()
            ternType = thenVal.type.takeIf { it != "void" && it != "object" } ?: "var"
            ternVar = nextTemp("tern")
            val declLine = "    ".repeat(openBlocks.size) + "$ternType $ternVar;"
            out.add(closed.headerOutIndex, declLine)
            out.add("    ".repeat(openBlocks.size + 1) + "$ternVar = ${thenVal.code};")
          } else {
            while (stack.size > closed.entryStackDepth) stack.removeLast()
          }
          out.add("    ".repeat(openBlocks.size) + "} else {")
          openBlocks.add(
            ControlBlock(
              kind = BlockKind.ELSE,
              endOffset = elseEnd,
              entryStackDepth = closed.entryStackDepth,
              ternaryVar = ternVar,
              ternaryType = ternType,
            ),
          )
          break
        } else {
          if (closed.kind == BlockKind.ELSE && closed.ternaryVar != null && stack.size > closed.entryStackDepth) {
            val elseVal = stack.removeLast()
            out.add("    ".repeat(openBlocks.size + 1) + "${closed.ternaryVar} = ${elseVal.code};")
            while (stack.size > closed.entryStackDepth) stack.removeLast()
            out.add("    ".repeat(openBlocks.size) + "}")
            stack.addLast(StackVal(closed.ternaryVar, closed.ternaryType, 100))
          } else {
            while (stack.size > closed.entryStackDepth) stack.removeLast()
            out.add("    ".repeat(openBlocks.size) + "}")
          }
        }
      }

      if (ins.isPrefixFe) {
        when (ins.opcode) {
          0x01 -> { // ceq
            val b = popOrDefault(); val a = popOrDefault()
            stack.addLast(StackVal("${wrapIfNeeded(a, 7)} == ${wrapIfNeeded(b, 7)}", "bool", 7))
          }
          0x02, 0x03 -> { // cgt / cgt.un
            val b = popOrDefault(); val a = popOrDefault()
            stack.addLast(StackVal("${wrapIfNeeded(a, 8)} > ${wrapIfNeeded(b, 8)}", "bool", 8))
          }
          0x04, 0x05 -> { // clt / clt.un
            val b = popOrDefault(); val a = popOrDefault()
            stack.addLast(StackVal("${wrapIfNeeded(a, 8)} < ${wrapIfNeeded(b, 8)}", "bool", 8))
          }
          0x06 -> { // ldftn
            val c = ctx.resolveCallableToken(ins.intOperand)
            stack.addLast(StackVal(c.name, "Action", 100))
          }
          0x07 -> { // ldvirtftn
            popOrDefault()
            val c = ctx.resolveCallableToken(ins.intOperand)
            stack.addLast(StackVal(c.name, "Action", 100))
          }
          0x09 -> stack.addLast(StackVal(argName(ins.intOperand), "object", 100))
          0x0A -> stack.addLast(StackVal(argName(ins.intOperand), "ref", 100))
          0x0B -> {
            val v = popOrDefault()
            val a = argName(ins.intOperand)
            emit("$a = ${v.code};")
          }
          0x0C -> stack.addLast(StackVal(localName(ins.intOperand), localTypes.getOrNull(ins.intOperand) ?: "var", 100))
          0x0D -> stack.addLast(StackVal(localName(ins.intOperand), "ref", 100))
          0x0E -> {
            val v = popOrDefault()
            val lIdx = ins.intOperand
            if (declaredLocals.add(lIdx)) {
              val t = localTypes.getOrNull(lIdx)?.takeIf { it != "object" } ?: v.type.takeIf { it != "void" && it != "object" } ?: "var"
              emit("$t ${localName(lIdx)} = ${v.code};")
            } else {
              emit("${localName(lIdx)} = ${v.code};")
            }
          }
          0x15 -> { // initobj
            val addr = popOrDefault()
            val t = ctx.resolveTypeToken(ins.intOperand)
            emit("${addr.code} = default($t);")
          }
          0x16 -> pendingConstrainedType = ctx.resolveTypeToken(ins.intOperand)
          else -> {}
        }
        continue
      }

      when (ins.opcode) {
        0x00 -> {} // nop
        0x02 -> stack.addLast(StackVal(argName(0), "object", 100))
        0x03 -> stack.addLast(StackVal(argName(1), "object", 100))
        0x04 -> stack.addLast(StackVal(argName(2), "object", 100))
        0x05 -> stack.addLast(StackVal(argName(3), "object", 100))
        0x06 -> stack.addLast(StackVal(localName(0), localTypes.getOrNull(0) ?: "var", 100))
        0x07 -> stack.addLast(StackVal(localName(1), localTypes.getOrNull(1) ?: "var", 100))
        0x08 -> stack.addLast(StackVal(localName(2), localTypes.getOrNull(2) ?: "var", 100))
        0x09 -> stack.addLast(StackVal(localName(3), localTypes.getOrNull(3) ?: "var", 100))
        in 0x0A..0x0D -> {
          val lIdx = ins.opcode - 0x0A
          val v = popOrDefault()
          val lType = localTypes.getOrNull(lIdx)?.takeIf { it != "object" } ?: v.type.takeIf { it != "void" && it != "object" } ?: "var"
          val formatted = formatForType(v, lType)
          if (declaredLocals.add(lIdx)) {
            emit("$lType ${localName(lIdx)} = $formatted;")
          } else {
            emit("${localName(lIdx)} = $formatted;")
          }
        }
        0x0E -> stack.addLast(StackVal(argName(ins.intOperand), "object", 100))
        0x0F -> stack.addLast(StackVal(argName(ins.intOperand), "ref", 100))
        0x10 -> {
          val v = popOrDefault()
          emit("${argName(ins.intOperand)} = ${v.code};")
        }
        0x11 -> stack.addLast(StackVal(localName(ins.intOperand), localTypes.getOrNull(ins.intOperand) ?: "var", 100))
        0x12 -> stack.addLast(StackVal(localName(ins.intOperand), "ref", 100))
        0x13 -> {
          val v = popOrDefault()
          val lIdx = ins.intOperand
          val lType = localTypes.getOrNull(lIdx)?.takeIf { it != "object" } ?: v.type.takeIf { it != "void" && it != "object" } ?: "var"
          val formatted = formatForType(v, lType)
          if (declaredLocals.add(lIdx)) {
            emit("$lType ${localName(lIdx)} = $formatted;")
          } else {
            emit("${localName(lIdx)} = $formatted;")
          }
        }
        0x14 -> stack.addLast(StackVal("null", "object", 100))
        0x15 -> stack.addLast(StackVal("-1", "int", 100))
        in 0x16..0x1E -> stack.addLast(StackVal((ins.opcode - 0x16).toString(), "int", 100))
        0x1F, 0x20 -> stack.addLast(StackVal(ins.intOperand.toString(), "int", 100))
        0x21 -> stack.addLast(StackVal("${ins.longOperand}L", "long", 100))
        0x22 -> stack.addLast(StackVal(formatFloatLiteral(ins.floatOperand), "float", 100))
        0x23 -> stack.addLast(StackVal(ins.doubleOperand.toString(), "double", 100))
        0x25 -> { // dup
          val top = stack.lastOrNull() ?: StackVal("null")
          if (top.code.startsWith("new ") || top.code.contains("(")) {
            stack.removeLast()
            val tName = top.type.takeIf { it != "void" && it != "object" } ?: "var"
            val tmp = nextTemp("tmp")
            emit("$tName $tmp = ${top.code};")
            val refVal = StackVal(tmp, top.type, 100)
            stack.addLast(refVal)
            stack.addLast(refVal)
          } else {
            stack.addLast(top)
          }
        }
        0x26 -> { // pop
          val dropped = popOrDefault()
          if (dropped.code.contains("(")) emit("${dropped.code};")
        }
        0x28, 0x6F -> { // call / callvirt
          val c = ctx.resolveCallableToken(ins.intOperand)
          val argCount = c.paramTypes.size
          val args = MutableList(argCount) { StackVal("null") }
          for (i in argCount - 1 downTo 0) args[i] = popOrDefault()
          val formattedArgs = args.mapIndexed { i, v ->
            val pType = c.paramTypes.getOrNull(i)
            StackVal(formatForType(v, pType), v.type, v.precedence)
          }
          val target = if (c.hasThis) popOrDefault("this") else null
          pendingConstrainedType = null

          val callResult = formatCallExpression(c, target, formattedArgs)
          if (c.returnType == "void") {
            if (callResult != null) emit("$callResult;")
          } else {
            stack.addLast(StackVal(callResult ?: "${c.ownerType}.${c.name}()", c.returnType, 95))
          }
        }
        0x2A -> { // ret
          if (returnType != "void" && stack.isNotEmpty()) {
            val retVal = popOrDefault()
            emit("return ${formatForType(retVal, returnType)};")
          } else if (openBlocks.isNotEmpty() || idx < instructions.lastIndex) {
            emit("return;")
          }
        }
        0x2B, 0x38 -> { // br.s / br
          val target = ins.branchTarget
          val loopStart = backEdgeLoops.entries.firstOrNull { it.value == nextOff }?.key
          if (loopStart != null && target < ins.offset) {
            // Normal end of while-loop iteration
          } else if (target > nextOff) {
            val isElseJump = openBlocks.lastOrNull()?.let { it.kind == BlockKind.IF && it.endOffset == nextOff } == true
            if (!isElseJump && target > ins.offset + 2) {
              emit("// jump -> IL_${target.toString(16)}")
            }
          }
        }
        0x2C, 0x39 -> { // brfalse.s / brfalse
          val cond = popOrDefault("flag")
          val target = ins.branchTarget
          if (target > nextOff) {
            val headerIdx = out.size
            val isWhileHeader = backEdgeLoops[ins.offset] == target || backEdgeLoops.keys.any { it in (ins.offset - 16)..ins.offset && backEdgeLoops[it] == target }
            if (isWhileHeader) {
              emit("while (${cond.code}) {")
              openBlocks.add(ControlBlock(BlockKind.WHILE, target, ins.offset, entryStackDepth = stack.size, headerOutIndex = headerIdx))
            } else {
              emit("if (${cond.code}) {")
              openBlocks.add(ControlBlock(BlockKind.IF, target, entryStackDepth = stack.size, headerOutIndex = headerIdx))
            }
          } else {
            emit("if (!(${cond.code})) /* -> IL_${target.toString(16)} */;")
          }
        }
        0x2D, 0x3A -> { // brtrue.s / brtrue
          val cond = popOrDefault("flag")
          val target = ins.branchTarget
          if (target > nextOff) {
            val headerIdx = out.size
            emit("if (${negateCondition(cond.code)}) {")
            openBlocks.add(ControlBlock(BlockKind.IF, target, entryStackDepth = stack.size, headerOutIndex = headerIdx))
          } else {
            emit("while (${cond.code}) /* back-edge IL_${target.toString(16)} */;")
          }
        }
        in 0x2E..0x37, in 0x3B..0x44 -> { // binary conditional branches
          val b = popOrDefault()
          val a = popOrDefault()
          val op = branchInverseCondition(ins.opcode)
          val target = ins.branchTarget
          if (target > nextOff) {
            val headerIdx = out.size
            emit("if (${wrapIfNeeded(a, 7)} $op ${wrapIfNeeded(b, 7)}) {")
            openBlocks.add(ControlBlock(BlockKind.IF, target, entryStackDepth = stack.size, headerOutIndex = headerIdx))
          } else {
            emit("if (${wrapIfNeeded(a, 7)} ${branchDirectCondition(ins.opcode)} ${wrapIfNeeded(b, 7)}) /* -> IL_${target.toString(16)} */;")
          }
        }
        0x58 -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 10)} + ${wrapIfNeeded(b, 10)}", a.type, 10)) }
        0x59 -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 10)} - ${wrapIfNeeded(b, 10)}", a.type, 10)) }
        0x5A -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 11)} * ${wrapIfNeeded(b, 11)}", a.type, 11)) }
        0x5B, 0x5C -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 11)} / ${wrapIfNeeded(b, 11)}", a.type, 11)) }
        0x5D, 0x5E -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 11)} % ${wrapIfNeeded(b, 11)}", a.type, 11)) }
        0x5F -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 5)} & ${wrapIfNeeded(b, 5)}", a.type, 5)) }
        0x60 -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 3)} | ${wrapIfNeeded(b, 3)}", a.type, 3)) }
        0x61 -> { val b = popOrDefault(); val a = popOrDefault(); stack.addLast(StackVal("${wrapIfNeeded(a, 4)} ^ ${wrapIfNeeded(b, 4)}", a.type, 4)) }
        0x65 -> { val a = popOrDefault(); stack.addLast(StackVal("-${wrapIfNeeded(a, 12)}", a.type, 12)) }
        0x66 -> { val a = popOrDefault(); stack.addLast(StackVal("~${wrapIfNeeded(a, 12)}", a.type, 12)) }
        0x67, 0x6B -> {} // conv.i1 / conv.r4
        0x6C -> {} // conv.r8
        0x69, 0x6A -> {} // conv.i4 / conv.i8
        0x72 -> stack.addLast(StackVal(ctx.readUserString(ins.intOperand), "string", 100))
        0x73 -> { // newobj
          val c = ctx.resolveCallableToken(ins.intOperand)
          val args = MutableList(c.paramTypes.size) { StackVal("null") }
          for (i in c.paramTypes.size - 1 downTo 0) args[i] = popOrDefault()
          val expr =
            if (c.paramTypes.size == 2 && c.paramTypes[0] == "object" && c.paramTypes[1] == "IntPtr") {
              val targetExpr = if (args[0].code == "null") args[1].code else "${args[0].code}.${args[1].code}"
              "new ${c.ownerType}($targetExpr)"
            } else {
              val fArgs = args.mapIndexed { i, v -> formatForType(v, c.paramTypes.getOrNull(i)) }
              "new ${c.ownerType}(${fArgs.joinToString(", ")})"
            }
          stack.addLast(StackVal(expr, c.ownerType, 100))
        }
        0x74, 0x75 -> { // castclass / isinst
          val v = popOrDefault()
          val t = ctx.resolveTypeToken(ins.intOperand)
          val expr = if (ins.opcode == 0x74) "($t)${wrapIfNeeded(v, 12)}" else "${wrapIfNeeded(v, 8)} as $t"
          stack.addLast(StackVal(expr, t, 12))
        }
        0x7B, 0x7C -> { // ldfld / ldflda
          val obj = popOrDefault("this")
          val (_, fname, ftype) = ctx.resolveFieldToken(ins.intOperand)
          val prefix = if (obj.code == "this") "this." else "${obj.code}."
          stack.addLast(StackVal("$prefix$fname", ftype, 100))
        }
        0x7D -> { // stfld
          val value = popOrDefault()
          val obj = popOrDefault("this")
          val (_, fname, ftype) = ctx.resolveFieldToken(ins.intOperand)
          val prefix = if (obj.code == "this") "this." else "${obj.code}."
          emit("$prefix$fname = ${formatForType(value, ftype)};")
        }
        0x7E, 0x7F -> { // ldsfld / ldsflda
          val (owner, fname, ftype) = ctx.resolveFieldToken(ins.intOperand)
          stack.addLast(StackVal("$owner.$fname", ftype, 100))
        }
        0x80 -> { // stsfld
          val value = popOrDefault()
          val (owner, fname, ftype) = ctx.resolveFieldToken(ins.intOperand)
          emit("$owner.$fname = ${formatForType(value, ftype)};")
        }
        0x8C -> {} // box (no-op in C# source)
        0x79, 0xA5 -> { // unbox / unbox.any
          val v = popOrDefault()
          val t = ctx.resolveTypeToken(ins.intOperand)
          stack.addLast(StackVal("($t)${wrapIfNeeded(v, 12)}", t, 12))
        }
        0x8D -> { // newarr
          val len = popOrDefault("0")
          val t = ctx.resolveTypeToken(ins.intOperand)
          stack.addLast(StackVal("new $t[${len.code}]", "$t[]", 100))
        }
        0x8E -> { // ldlen
          val arr = popOrDefault("arr")
          stack.addLast(StackVal("${arr.code}.Length", "int", 100))
        }
        in 0x90..0x9A, 0xA3 -> { // ldelem*
          val i = popOrDefault("0"); val arr = popOrDefault("arr")
          stack.addLast(StackVal("${arr.code}[${i.code}]", "object", 100))
        }
        in 0x9C..0xA2, 0xA4 -> { // stelem*
          val v = popOrDefault(); val i = popOrDefault("0"); val arr = popOrDefault("arr")
          emit("${arr.code}[${i.code}] = ${v.code};")
        }
        0xDC -> { // endfinally
          emit("// endfinally")
        }
        0xDD, 0xDE -> { // leave / leave.s
          if (ins.branchTarget >= 0 && ins.branchTarget > nextOff) {
            emit("// leave -> IL_${ins.branchTarget.toString(16)}")
          }
        }
        else -> {
          emit("// [CIL] ${ins.mnemonic}")
        }
      }
    }

    while (openBlocks.isNotEmpty()) {
      openBlocks.removeAt(openBlocks.lastIndex)
      out.add("    ".repeat(openBlocks.size) + "}")
    }
    return out
  }

  private fun formatCallExpression(
    c: ResolvedCallable,
    target: StackVal?,
    args: List<StackVal>,
  ): String? {
    // Handle compiler-generated base() call inside constructor
    if (!c.hasThis && c.name == ".ctor") return null
    if (c.hasThis && target?.code == "this" && c.name == ".ctor") return null

    // Operator overloads
    when (c.name) {
      "op_Equality" -> if (args.size == 2) return "${args[0].code} == ${args[1].code}"
      "op_Inequality" -> if (args.size == 2) return "${args[0].code} != ${args[1].code}"
      "op_Addition" -> if (args.size == 2) return "${args[0].code} + ${args[1].code}"
      "op_Subtraction" -> if (args.size == 2) return "${args[0].code} - ${args[1].code}"
      "op_Multiply" -> if (args.size == 2) return "${args[0].code} * ${args[1].code}"
      "op_Division" -> if (args.size == 2) return "${args[0].code} / ${args[1].code}"
      "op_UnaryNegation" -> if (args.size == 1) return "-${args[0].code}"
      "op_Implicit" -> if (args.size == 1) return args[0].code
    }

    val receiver = if (c.hasThis) (target?.code ?: "this") else c.ownerType

    // Property getters and setters
    if (c.name.startsWith("get_") && args.isEmpty()) {
      val prop = c.name.removePrefix("get_")
      return "$receiver.$prop"
    }
    if (c.name.startsWith("set_") && args.size == 1) {
      val prop = c.name.removePrefix("set_")
      return "$receiver.$prop = ${args[0].code}"
    }

    val argList = args.joinToString(", ") { it.code }
    return "$receiver.${c.name}($argList)"
  }

  private fun negateCondition(cond: String): String =
    when {
      cond.contains(" == ") -> cond.replace(" == ", " != ")
      cond.contains(" != ") -> cond.replace(" != ", " == ")
      cond.contains(" < ") -> cond.replace(" < ", " >= ")
      cond.contains(" > ") -> cond.replace(" > ", " <= ")
      cond.contains(" <= ") -> cond.replace(" <= ", " > ")
      cond.contains(" >= ") -> cond.replace(" >= ", " < ")
      cond.startsWith("!") -> cond.removePrefix("!")
      else -> "!($cond)"
    }

  private fun branchInverseCondition(opcode: Int): String =
    when (opcode) {
      0x2E, 0x3B -> "!=" // beq -> enter if != false, i.e., ==
      0x2F, 0x3C, 0x34, 0x41 -> "<"  // bge -> enter when <
      0x30, 0x3D, 0x35, 0x42 -> "<=" // bgt -> enter when <=
      0x31, 0x3E, 0x36, 0x43 -> ">"  // ble -> enter when >
      0x32, 0x3F, 0x37, 0x44 -> ">=" // blt -> enter when >=
      0x33, 0x40 -> "=="             // bne.un -> enter when ==
      else -> "!="
    }

  private fun branchDirectCondition(opcode: Int): String =
    when (opcode) {
      0x2E, 0x3B -> "=="
      0x2F, 0x3C, 0x34, 0x41 -> ">="
      0x30, 0x3D, 0x35, 0x42 -> ">"
      0x31, 0x3E, 0x36, 0x43 -> "<="
      0x32, 0x3F, 0x37, 0x44 -> "<"
      0x33, 0x40 -> "!="
      else -> "=="
    }

  private fun formatFloatLiteral(f: Float): String {
    val s = f.toString()
    return if (s.contains('.') || s.contains('E') || s.contains('e')) "${s}f" else "${s}.0f"
  }

  internal fun decodeInstructions(il: ByteArray): List<CilInstruction> {
    val result = mutableListOf<CilInstruction>()
    var i = 0
    while (i < il.size) {
      val off = i
      val op = il[i++].toInt() and 0xFF
      if (op == 0xFE) {
        if (i >= il.size) break
        val sub = il[i++].toInt() and 0xFF
        val (name, opBytes) = decodeFeOpcode(sub)
        val operand = readSignedOperand(il, i, opBytes)
        i = minOf(i + opBytes, il.size)
        result.add(CilInstruction(off, i - off, sub, isPrefixFe = true, mnemonic = name, intOperand = operand))
        continue
      }

      if (op == 0x45) { // switch
        val count = if (i + 4 <= il.size) readI32LE(il, i).coerceIn(0, 1024) else 0
        i = minOf(i + 4, il.size)
        val baseNext = i + count * 4
        val targets = IntArray(count)
        for (k in 0 until count) {
          if (i + 4 <= il.size) {
            targets[k] = baseNext + readI32LE(il, i)
            i += 4
          }
        }
        result.add(CilInstruction(off, i - off, op, isPrefixFe = false, mnemonic = "switch", switchTargets = targets))
        continue
      }

      val spec = decodeSingleByteOpcode(op)
      val name = spec.first
      val opBytes = spec.second
      val kind = spec.third
      var intOp = 0
      var longOp = 0L
      var floatOp = 0f
      var doubleOp = 0.0
      var branchTarget = -1

      if (i + opBytes <= il.size) {
        when (kind) {
          OpKind.I8 -> intOp = il[i].toInt() // signed
          OpKind.U8 -> intOp = il[i].toInt() and 0xFF
          OpKind.I32 -> intOp = readI32LE(il, i)
          OpKind.I64 -> longOp = readI64LE(il, i)
          OpKind.R32 -> floatOp = Float.fromBits(readI32LE(il, i))
          OpKind.R64 -> doubleOp = Double.fromBits(readI64LE(il, i))
          OpKind.BR_S -> branchTarget = (i + 1) + il[i].toInt()
          OpKind.BR -> branchTarget = (i + 4) + readI32LE(il, i)
          OpKind.NONE -> {}
        }
      }
      i = minOf(i + opBytes, il.size)
      result.add(
        CilInstruction(
          offset = off,
          size = i - off,
          opcode = op,
          isPrefixFe = false,
          mnemonic = name,
          intOperand = intOp,
          longOperand = longOp,
          floatOperand = floatOp,
          doubleOperand = doubleOp,
          branchTarget = branchTarget,
        ),
      )
    }
    return result
  }

  private fun readSignedOperand(il: ByteArray, pos: Int, len: Int): Int {
    if (pos + len > il.size) return 0
    return when (len) {
      1 -> il[pos].toInt()
      2 -> (il[pos].toInt() and 0xFF) or ((il[pos + 1].toInt() and 0xFF) shl 8)
      4 -> readI32LE(il, pos)
      else -> 0
    }
  }

  private fun readI32LE(b: ByteArray, pos: Int): Int =
    (b[pos].toInt() and 0xFF) or
      ((b[pos + 1].toInt() and 0xFF) shl 8) or
      ((b[pos + 2].toInt() and 0xFF) shl 16) or
      ((b[pos + 3].toInt() and 0xFF) shl 24)

  private fun readI64LE(b: ByteArray, pos: Int): Long =
    (readI32LE(b, pos).toLong() and 0xFFFFFFFFL) or (readI32LE(b, pos + 4).toLong() shl 32)

  private enum class OpKind { NONE, I8, U8, I32, I64, R32, R64, BR_S, BR }

  private fun decodeFeOpcode(sub: Int): Pair<String, Int> =
    when (sub) {
      0x00 -> "arglist" to 0
      0x01 -> "ceq" to 0
      0x02 -> "cgt" to 0
      0x03 -> "cgt.un" to 0
      0x04 -> "clt" to 0
      0x05 -> "clt.un" to 0
      0x06 -> "ldftn" to 4
      0x07 -> "ldvirtftn" to 4
      0x09 -> "ldarg" to 2
      0x0A -> "ldarga" to 2
      0x0B -> "starg" to 2
      0x0C -> "ldloc" to 2
      0x0D -> "ldloca" to 2
      0x0E -> "stloc" to 2
      0x0F -> "localloc" to 0
      0x11 -> "endfilter" to 0
      0x12 -> "unaligned." to 1
      0x13 -> "volatile." to 0
      0x14 -> "tail." to 0
      0x15 -> "initobj" to 4
      0x16 -> "constrained." to 4
      0x17 -> "cpblk" to 0
      0x18 -> "initblk" to 0
      0x1A -> "rethrow" to 0
      0x1C -> "sizeof" to 4
      0x1D -> "refanytype" to 0
      0x1E -> "readonly." to 0
      else -> String.format(Locale.US, "fe_0x%02x", sub) to 0
    }

  private fun decodeSingleByteOpcode(op: Int): Triple<String, Int, OpKind> =
    when (op) {
      0x00 -> Triple("nop", 0, OpKind.NONE)
      0x01 -> Triple("break", 0, OpKind.NONE)
      0x02 -> Triple("ldarg.0", 0, OpKind.NONE)
      0x03 -> Triple("ldarg.1", 0, OpKind.NONE)
      0x04 -> Triple("ldarg.2", 0, OpKind.NONE)
      0x05 -> Triple("ldarg.3", 0, OpKind.NONE)
      0x06 -> Triple("ldloc.0", 0, OpKind.NONE)
      0x07 -> Triple("ldloc.1", 0, OpKind.NONE)
      0x08 -> Triple("ldloc.2", 0, OpKind.NONE)
      0x09 -> Triple("ldloc.3", 0, OpKind.NONE)
      0x0A -> Triple("stloc.0", 0, OpKind.NONE)
      0x0B -> Triple("stloc.1", 0, OpKind.NONE)
      0x0C -> Triple("stloc.2", 0, OpKind.NONE)
      0x0D -> Triple("stloc.3", 0, OpKind.NONE)
      0x0E -> Triple("ldarg.s", 1, OpKind.U8)
      0x0F -> Triple("ldarga.s", 1, OpKind.U8)
      0x10 -> Triple("starg.s", 1, OpKind.U8)
      0x11 -> Triple("ldloc.s", 1, OpKind.U8)
      0x12 -> Triple("ldloca.s", 1, OpKind.U8)
      0x13 -> Triple("stloc.s", 1, OpKind.U8)
      0x14 -> Triple("ldnull", 0, OpKind.NONE)
      0x15 -> Triple("ldc.i4.m1", 0, OpKind.NONE)
      0x16 -> Triple("ldc.i4.0", 0, OpKind.NONE)
      0x17 -> Triple("ldc.i4.1", 0, OpKind.NONE)
      0x18 -> Triple("ldc.i4.2", 0, OpKind.NONE)
      0x19 -> Triple("ldc.i4.3", 0, OpKind.NONE)
      0x1A -> Triple("ldc.i4.4", 0, OpKind.NONE)
      0x1B -> Triple("ldc.i4.5", 0, OpKind.NONE)
      0x1C -> Triple("ldc.i4.6", 0, OpKind.NONE)
      0x1D -> Triple("ldc.i4.7", 0, OpKind.NONE)
      0x1E -> Triple("ldc.i4.8", 0, OpKind.NONE)
      0x1F -> Triple("ldc.i4.s", 1, OpKind.I8)
      0x20 -> Triple("ldc.i4", 4, OpKind.I32)
      0x21 -> Triple("ldc.i8", 8, OpKind.I64)
      0x22 -> Triple("ldc.r4", 4, OpKind.R32)
      0x23 -> Triple("ldc.r8", 8, OpKind.R64)
      0x25 -> Triple("dup", 0, OpKind.NONE)
      0x26 -> Triple("pop", 0, OpKind.NONE)
      0x27 -> Triple("jmp", 4, OpKind.I32)
      0x28 -> Triple("call", 4, OpKind.I32)
      0x29 -> Triple("calli", 4, OpKind.I32)
      0x2A -> Triple("ret", 0, OpKind.NONE)
      0x2B -> Triple("br.s", 1, OpKind.BR_S)
      0x2C -> Triple("brfalse.s", 1, OpKind.BR_S)
      0x2D -> Triple("brtrue.s", 1, OpKind.BR_S)
      0x2E -> Triple("beq.s", 1, OpKind.BR_S)
      0x2F -> Triple("bge.s", 1, OpKind.BR_S)
      0x30 -> Triple("bgt.s", 1, OpKind.BR_S)
      0x31 -> Triple("ble.s", 1, OpKind.BR_S)
      0x32 -> Triple("blt.s", 1, OpKind.BR_S)
      0x33 -> Triple("bne.un.s", 1, OpKind.BR_S)
      0x34 -> Triple("bge.un.s", 1, OpKind.BR_S)
      0x35 -> Triple("bgt.un.s", 1, OpKind.BR_S)
      0x36 -> Triple("ble.un.s", 1, OpKind.BR_S)
      0x37 -> Triple("blt.un.s", 1, OpKind.BR_S)
      0x38 -> Triple("br", 4, OpKind.BR)
      0x39 -> Triple("brfalse", 4, OpKind.BR)
      0x3A -> Triple("brtrue", 4, OpKind.BR)
      0x3B -> Triple("beq", 4, OpKind.BR)
      0x3C -> Triple("bge", 4, OpKind.BR)
      0x3D -> Triple("bgt", 4, OpKind.BR)
      0x3E -> Triple("ble", 4, OpKind.BR)
      0x3F -> Triple("blt", 4, OpKind.BR)
      0x40 -> Triple("bne.un", 4, OpKind.BR)
      0x41 -> Triple("bge.un", 4, OpKind.BR)
      0x42 -> Triple("bgt.un", 4, OpKind.BR)
      0x43 -> Triple("ble.un", 4, OpKind.BR)
      0x44 -> Triple("blt.un", 4, OpKind.BR)
      in 0x46..0x57 -> Triple("ldind", 0, OpKind.NONE)
      0x58 -> Triple("add", 0, OpKind.NONE)
      0x59 -> Triple("sub", 0, OpKind.NONE)
      0x5A -> Triple("mul", 0, OpKind.NONE)
      0x5B -> Triple("div", 0, OpKind.NONE)
      0x5C -> Triple("div.un", 0, OpKind.NONE)
      0x5D -> Triple("rem", 0, OpKind.NONE)
      0x5E -> Triple("rem.un", 0, OpKind.NONE)
      0x5F -> Triple("and", 0, OpKind.NONE)
      0x60 -> Triple("or", 0, OpKind.NONE)
      0x61 -> Triple("xor", 0, OpKind.NONE)
      0x62 -> Triple("shl", 0, OpKind.NONE)
      0x63 -> Triple("shr", 0, OpKind.NONE)
      0x64 -> Triple("shr.un", 0, OpKind.NONE)
      0x65 -> Triple("neg", 0, OpKind.NONE)
      0x66 -> Triple("not", 0, OpKind.NONE)
      in 0x67..0x6E -> Triple("conv", 0, OpKind.NONE)
      0x6F -> Triple("callvirt", 4, OpKind.I32)
      0x70 -> Triple("cpobj", 4, OpKind.I32)
      0x71 -> Triple("ldobj", 4, OpKind.I32)
      0x72 -> Triple("ldstr", 4, OpKind.I32)
      0x73 -> Triple("newobj", 4, OpKind.I32)
      0x74 -> Triple("castclass", 4, OpKind.I32)
      0x75 -> Triple("isinst", 4, OpKind.I32)
      0x76 -> Triple("conv.r.un", 0, OpKind.NONE)
      0x79 -> Triple("unbox", 4, OpKind.I32)
      0x7A -> Triple("throw", 0, OpKind.NONE)
      0x7B -> Triple("ldfld", 4, OpKind.I32)
      0x7C -> Triple("ldflda", 4, OpKind.I32)
      0x7D -> Triple("stfld", 4, OpKind.I32)
      0x7E -> Triple("ldsfld", 4, OpKind.I32)
      0x7F -> Triple("ldsflda", 4, OpKind.I32)
      0x80 -> Triple("stsfld", 4, OpKind.I32)
      0x81 -> Triple("stobj", 4, OpKind.I32)
      in 0x82..0x8B -> Triple("conv.ovf", 0, OpKind.NONE)
      0x8C -> Triple("box", 4, OpKind.I32)
      0x8D -> Triple("newarr", 4, OpKind.I32)
      0x8E -> Triple("ldlen", 0, OpKind.NONE)
      0x8F -> Triple("ldelema", 4, OpKind.I32)
      in 0x90..0xA2 -> Triple("elem", 0, OpKind.NONE)
      0xA3 -> Triple("ldelem", 4, OpKind.I32)
      0xA4 -> Triple("stelem", 4, OpKind.I32)
      0xA5 -> Triple("unbox.any", 4, OpKind.I32)
      0xC2 -> Triple("refanyval", 4, OpKind.I32)
      0xC3 -> Triple("ckfinite", 0, OpKind.NONE)
      0xC6 -> Triple("mkrefany", 4, OpKind.I32)
      0xD0 -> Triple("ldtoken", 4, OpKind.I32)
      in 0xD1..0xDB -> Triple("conv", 0, OpKind.NONE)
      0xDC -> Triple("endfinally", 0, OpKind.NONE)
      0xDD -> Triple("leave", 4, OpKind.BR)
      0xDE -> Triple("leave.s", 1, OpKind.BR_S)
      else -> Triple(String.format(Locale.US, "op_0x%02x", op), 0, OpKind.NONE)
    }
}
