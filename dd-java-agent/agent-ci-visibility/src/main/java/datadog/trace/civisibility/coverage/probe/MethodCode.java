package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.LINE_OFF_INSTRUCTION;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.LINE_ZERO;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.LOCAL_VARIABLE_OFF_INSTRUCTION;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.MALFORMED;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.OTHER_CODE_ATTRIBUTE;
import static datadog.trace.civisibility.coverage.probe.UnsupportedClassException.TYPE_ANNOTATIONS;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IINC;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKEDYNAMIC;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.ISTORE;
import static org.objectweb.asm.Opcodes.JSR;
import static org.objectweb.asm.Opcodes.LDC;
import static org.objectweb.asm.Opcodes.LOOKUPSWITCH;
import static org.objectweb.asm.Opcodes.MULTIANEWARRAY;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RET;
import static org.objectweb.asm.Opcodes.TABLESWITCH;

import java.util.Arrays;

/**
 * The code of one method, read in place from its class file: its instructions, jump targets, line
 * numbers, exception handlers and the code attributes the patched code keeps. Reading stops with
 * {@link UnsupportedClassException} on anything the patcher leaves to the ASM transformer.
 */
final class MethodCode {

  static final int LINE_NUMBERS = 1;
  static final int LOCAL_VARIABLES = 2;

  final ClassFile classFile;
  final int access;
  final String name;
  final String descriptor;
  final int maxStack;
  final int maxLocals;

  /** The offset of the first code byte in the class file. */
  final int codeStart;

  final int codeLength;

  /** The number of instructions. */
  int count;

  /** The code offset of each instruction, then the code length. */
  int[] offset;

  /** The instruction at each code offset, the instruction count at the code length, else -1. */
  int[] indexAt;

  /** The opcode of each instruction as ASM's tree API reads it. */
  int[] opcode;

  /** The instruction each jump goes to; -1 for other instructions. */
  int[] target;

  /** The instructions each switch goes to, its default first; null for other instructions. */
  int[][] switchTargets;

  /** Whether the code has JSR or RET instructions. */
  boolean hasSubroutines;

  /** The line of the last line table entry at each instruction; -1 where there is none. */
  int[] line;

  boolean hasLines;

  int handlerCount;

  /** The first instruction each handler covers. */
  int[] handlerStart;

  /** The instruction after the last one each handler covers, or the instruction count. */
  int[] handlerEnd;

  int[] handlerEntry;

  /** The class constant of the exceptions each handler catches; 0 if it catches all. */
  int[] handlerType;

  /** The line number and local variable tables: their name constant, content offset and kind. */
  int tableCount;

  int[] tableName = new int[4];
  int[] tableContent = new int[4];
  int[] tableKind = new int[4];

  /** The content offset of the StackMapTable attribute, or -1. */
  int stackMapTable = -1;

  int stackMapTableName;

  /**
   * @param code the offset of the Code attribute's content
   */
  MethodCode(ClassFile classFile, int access, String name, String descriptor, int code) {
    this.classFile = classFile;
    this.access = access;
    this.name = name;
    this.descriptor = descriptor;
    this.maxStack = classFile.readUnsignedShort(code);
    this.maxLocals = classFile.readUnsignedShort(code + 2);
    this.codeLength = classFile.readInt(code + 4);
    this.codeStart = code + 8;
    if (codeLength <= 0 || codeStart + codeLength > classFile.bytes.length) {
      throw new UnsupportedClassException(MALFORMED);
    }
    readInstructions();
    readTargets();
    int attributes = readHandlers(codeStart + codeLength);
    readAttributes(attributes);
  }

  private void readInstructions() {
    byte[] bytes = classFile.bytes;
    int[] offsets = new int[codeLength + 1];
    int instructions = 0;
    int at = 0;
    while (at < codeLength) {
      offsets[instructions++] = at;
      int opcode = bytes[codeStart + at] & 0xFF;
      int length = InstructionSet.fixedLength(opcode);
      if (length == 0) {
        length = variableLength(opcode, at);
      }
      if (length < 0) {
        throw new UnsupportedClassException(MALFORMED);
      }
      at += length;
    }
    if (at != codeLength) {
      throw new UnsupportedClassException(MALFORMED);
    }
    offsets[instructions] = codeLength;
    count = instructions;
    offset = offsets;
    indexAt = new int[codeLength + 1];
    Arrays.fill(indexAt, -1);
    for (int i = 0; i <= instructions; i++) {
      indexAt[offsets[i]] = i;
    }
  }

  /** The length of a switch or wide instruction at code offset {@code at}. */
  private int variableLength(int opcode, int at) {
    int padding = InstructionSet.switchPadding(at);
    int base = at + 1 + padding;
    if (opcode == TABLESWITCH) {
      requireCode(base + 12);
      long cases = (long) readCodeInt(base + 8) - readCodeInt(base + 4) + 1;
      requireCode(base + 12 + 4 * Math.max(cases, 0));
      if (cases <= 0) {
        throw new UnsupportedClassException(MALFORMED);
      }
      return (int) (1 + padding + 12 + 4 * cases);
    } else if (opcode == LOOKUPSWITCH) {
      requireCode(base + 8);
      long pairs = readCodeInt(base + 4);
      if (pairs < 0) {
        throw new UnsupportedClassException(MALFORMED);
      }
      requireCode(base + 8 + 8 * pairs);
      return (int) (1 + padding + 8 + 8 * pairs);
    }
    requireCode(at + 2);
    int inner = classFile.bytes[codeStart + at + 1] & 0xFF;
    if (inner == IINC) {
      return 6;
    } else if ((inner >= ILOAD && inner <= ALOAD) || (inner >= ISTORE && inner <= ASTORE)) {
      return 4;
    } else if (inner == RET) {
      return 4;
    }
    return -1;
  }

  private void requireCode(long end) {
    if (end > codeLength) {
      throw new UnsupportedClassException(MALFORMED);
    }
  }

  private int readCodeInt(int at) {
    return classFile.readInt(codeStart + at);
  }

  private void readTargets() {
    byte[] bytes = classFile.bytes;
    opcode = new int[count];
    target = new int[count];
    Arrays.fill(target, -1);
    switchTargets = new int[count][];
    for (int i = 0; i < count; i++) {
      int at = offset[i];
      int raw = bytes[codeStart + at] & 0xFF;
      int treeOpcode =
          raw == InstructionSet.WIDE
              ? bytes[codeStart + at + 1] & 0xFF
              : InstructionSet.treeOpcode(raw);
      opcode[i] = treeOpcode;
      if (treeOpcode == JSR || treeOpcode == RET) {
        hasSubroutines = true;
      }
      if (InstructionSet.isShortJump(raw)) {
        target[i] = instructionAt(at + classFile.readShort(codeStart + at + 1));
      } else if (raw == InstructionSet.GOTO_W || raw == InstructionSet.JSR_W) {
        target[i] = instructionAt(at + readCodeInt(at + 1));
      } else if (raw == TABLESWITCH || raw == LOOKUPSWITCH) {
        switchTargets[i] = readSwitchTargets(raw, at);
      }
    }
  }

  private int[] readSwitchTargets(int opcode, int at) {
    int base = at + 1 + InstructionSet.switchPadding(at);
    int cases;
    int caseOffsets;
    int stride;
    if (opcode == TABLESWITCH) {
      cases = readCodeInt(base + 8) - readCodeInt(base + 4) + 1;
      caseOffsets = base + 12;
      stride = 4;
    } else {
      cases = readCodeInt(base + 4);
      caseOffsets = base + 12; // past each pair's key
      stride = 8;
    }
    int[] targets = new int[cases + 1];
    targets[0] = instructionAt(at + readCodeInt(base));
    for (int k = 0; k < cases; k++) {
      targets[k + 1] = instructionAt(at + readCodeInt(caseOffsets + k * stride));
    }
    return targets;
  }

  /** The instruction at a code offset that must be an instruction's. */
  int instructionAt(int at) {
    if (at < 0 || at >= codeLength || indexAt[at] < 0) {
      throw new UnsupportedClassException(MALFORMED);
    }
    return indexAt[at];
  }

  /** The instruction at a code offset that must be an instruction's or the code length. */
  private int instructionOrEnd(int at) {
    if (at < 0 || at > codeLength || indexAt[at] < 0) {
      throw new UnsupportedClassException(MALFORMED);
    }
    return indexAt[at];
  }

  /**
   * @return the offset of the Code attribute's attributes count
   */
  private int readHandlers(int table) {
    int handlers = classFile.readUnsignedShort(table);
    handlerCount = handlers;
    handlerStart = new int[handlers];
    handlerEnd = new int[handlers];
    handlerEntry = new int[handlers];
    handlerType = new int[handlers];
    for (int h = 0; h < handlers; h++) {
      int entry = table + 2 + 8 * h;
      handlerStart[h] = instructionAt(classFile.readUnsignedShort(entry));
      handlerEnd[h] = instructionOrEnd(classFile.readUnsignedShort(entry + 2));
      handlerEntry[h] = instructionAt(classFile.readUnsignedShort(entry + 4));
      handlerType[h] = classFile.readUnsignedShort(entry + 6);
    }
    return table + 2 + 8 * handlers;
  }

  private void readAttributes(int attributes) {
    line = new int[count];
    Arrays.fill(line, -1);
    int attributeCount = classFile.readUnsignedShort(attributes);
    int at = attributes + 2;
    for (int k = 0; k < attributeCount; k++) {
      int name = classFile.readUnsignedShort(at);
      int content = at + 6;
      at = content + classFile.readInt(at + 2);
      if (classFile.utf8Equals(name, "LineNumberTable")) {
        readLineNumbers(content);
        addTable(name, content, LINE_NUMBERS);
      } else if (classFile.utf8Equals(name, "LocalVariableTable")
          || classFile.utf8Equals(name, "LocalVariableTypeTable")) {
        checkLocalVariables(content);
        addTable(name, content, LOCAL_VARIABLES);
      } else if (classFile.utf8Equals(name, "StackMapTable")) {
        if (stackMapTable >= 0) {
          throw new UnsupportedClassException(MALFORMED);
        }
        stackMapTable = content;
        stackMapTableName = name;
      } else if (classFile.utf8Equals(name, "RuntimeVisibleTypeAnnotations")
          || classFile.utf8Equals(name, "RuntimeInvisibleTypeAnnotations")) {
        throw new UnsupportedClassException(TYPE_ANNOTATIONS);
      } else {
        throw new UnsupportedClassException(OTHER_CODE_ATTRIBUTE);
      }
    }
  }

  private void addTable(int name, int content, int kind) {
    if (tableCount == tableName.length) {
      tableName = Arrays.copyOf(tableName, tableCount * 2);
      tableContent = Arrays.copyOf(tableContent, tableCount * 2);
      tableKind = Arrays.copyOf(tableKind, tableCount * 2);
    }
    tableName[tableCount] = name;
    tableContent[tableCount] = content;
    tableKind[tableCount] = kind;
    tableCount++;
  }

  /**
   * Reads line numbers. Of several entries at one instruction the last one counts, as for ASM's
   * tree API. ASM drops entries off an instruction and ignores line 0 in places; the patcher leaves
   * such code to it.
   */
  private void readLineNumbers(int content) {
    int entries = classFile.readUnsignedShort(content);
    for (int k = 0; k < entries; k++) {
      int entry = content + 2 + 4 * k;
      int pc = classFile.readUnsignedShort(entry);
      int lineNumber = classFile.readUnsignedShort(entry + 2);
      if (pc >= codeLength || indexAt[pc] < 0) {
        throw new UnsupportedClassException(LINE_OFF_INSTRUCTION);
      }
      if (lineNumber == 0) {
        throw new UnsupportedClassException(LINE_ZERO);
      }
      line[indexAt[pc]] = lineNumber;
      hasLines = true;
    }
  }

  private void checkLocalVariables(int content) {
    int entries = classFile.readUnsignedShort(content);
    for (int k = 0; k < entries; k++) {
      int entry = content + 2 + 10 * k;
      int start = classFile.readUnsignedShort(entry);
      int end = start + classFile.readUnsignedShort(entry + 2);
      if (start >= codeLength || indexAt[start] < 0 || end > codeLength || indexAt[end] < 0) {
        throw new UnsupportedClassException(LOCAL_VARIABLE_OFF_INSTRUCTION);
      }
    }
  }

  /** The class file opcode of an instruction. */
  int rawOpcode(int i) {
    return classFile.bytes[codeStart + offset[i]] & 0xFF;
  }

  /** The constant pool tag of the constant an LDC instruction loads. */
  int ldcConstantTag(int i) {
    return classFile.tag(ldcConstant(i));
  }

  private int ldcConstant(int i) {
    int at = codeStart + offset[i];
    return rawOpcode(i) == LDC ? classFile.readByte(at + 1) : classFile.readUnsignedShort(at + 1);
  }

  /** The constant pool index operand of a field, method, type or invokedynamic instruction. */
  int constantOperand(int i) {
    return classFile.readUnsignedShort(codeStart + offset[i] + 1);
  }

  /** Whether an INVOKESPECIAL instruction calls a constructor. */
  boolean callsConstructor(int i) {
    return classFile.utf8Equals(classFile.referenceName(constantOperand(i)), "<init>");
  }

  /** How many operand stack slots an instruction pushes, less those it pops. */
  int stackDelta(int i) {
    int op = opcode[i];
    switch (op) {
      case LDC:
        int constant = ldcConstant(i);
        int tag = classFile.tag(constant);
        if (tag == ClassFile.CONSTANT_LONG || tag == ClassFile.CONSTANT_DOUBLE) {
          return 2;
        }
        return tag == ClassFile.CONSTANT_DYNAMIC
            ? valueSize(classFile.referenceDescriptor(constant))
            : 1;
      case GETSTATIC:
        return valueSize(classFile.referenceDescriptor(constantOperand(i)));
      case PUTSTATIC:
        return -valueSize(classFile.referenceDescriptor(constantOperand(i)));
      case GETFIELD:
        return valueSize(classFile.referenceDescriptor(constantOperand(i))) - 1;
      case PUTFIELD:
        return -valueSize(classFile.referenceDescriptor(constantOperand(i))) - 1;
      case INVOKEVIRTUAL:
      case INVOKESPECIAL:
      case INVOKEINTERFACE:
        return invokeDelta(classFile.referenceDescriptor(constantOperand(i)), false);
      case INVOKESTATIC:
      case INVOKEDYNAMIC:
        return invokeDelta(classFile.referenceDescriptor(constantOperand(i)), true);
      case MULTIANEWARRAY:
        return 1 - classFile.readByte(codeStart + offset[i] + 3);
      default:
        return InstructionSet.stackDelta(op);
    }
  }

  /** The stack slots of a value with the field descriptor in a UTF8 constant. */
  private int valueSize(int descriptor) {
    int first = classFile.bytes[classFile.utf8Start(descriptor)];
    return first == 'J' || first == 'D' ? 2 : 1;
  }

  private int invokeDelta(int descriptor, boolean noReceiver) {
    int sizes = argumentsAndReturnSizes(descriptor);
    return (sizes & 3) - (sizes >> 2) + (noReceiver ? 1 : 0);
  }

  /**
   * The slots of a method's arguments, plus one for a receiver, shifted left by 2, plus the slots
   * of its return value, as {@code Type.getArgumentsAndReturnSizes} computes them.
   */
  private int argumentsAndReturnSizes(int descriptor) {
    byte[] bytes = classFile.bytes;
    int at = classFile.utf8Start(descriptor) + 1;
    int argumentsSize = 1;
    int c = bytes[at];
    while (c != ')') {
      if (c == 'J' || c == 'D') {
        at++;
        argumentsSize += 2;
      } else {
        while (bytes[at] == '[') {
          at++;
        }
        if (bytes[at++] == 'L') {
          while (bytes[at] != ';') {
            at++;
          }
          at++;
        }
        argumentsSize += 1;
      }
      c = bytes[at];
    }
    c = bytes[at + 1];
    if (c == 'V') {
      return argumentsSize << 2;
    }
    return argumentsSize << 2 | (c == 'J' || c == 'D' ? 2 : 1);
  }
}
