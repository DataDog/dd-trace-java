package datadog.trace.civisibility.coverage.probe;

import datadog.trace.civisibility.coverage.probe.MethodProbeInserter.ProbePlacementException;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Inserts line probes into a class file without ASM, method by method with {@link CodePatcher}. The
 * class file is copied as it is, with constants appended to its constant pool and the Code
 * attribute of each method that gets probes replaced. Classes are patched as {@link
 * ProbeClassTransformer} instruments them with ASM, probe ids included.
 */
final class ClassFilePatcher {

  private static final String RECORDER = MethodProbeInserter.RECORDER;
  private static final String PROBES_TYPE = "[Z";

  final ClassFile classFile;
  private final ConstantPoolAppender constants;
  private final int classId;

  private int methodCount;
  private int[] methodStart;
  private String[] methodName;
  private String[] methodDescriptor;

  /** The offset of each method's Code attribute, -1 for a method without one. */
  private int[] codeAttribute;

  private MethodCode[] methodCode;
  private final Set<String> skippedMethods = new HashSet<>();

  private int probesMethod;
  private int recordMethod;
  private int recordInitMethod;
  private int hitMethod;
  private int recordThrownMethod;
  private int recordInitThrownMethod;
  private int probesField;
  private int probesType;
  private int throwableType;
  private int stackMapTableName;

  private ClassFilePatcher(ClassFile classFile, int classId) {
    this.classFile = classFile;
    this.constants = new ConstantPoolAppender(classFile);
    this.classId = classId;
  }

  /**
   * @return the patched class, or {@code null} if it has no lines to cover
   * @throws UnsupportedClassException if the class is to be left to the ASM transformer
   */
  @Nullable
  static ProbeClassTransformer.Instrumented instrument(ClassFile classFile, int classId) {
    return new ClassFilePatcher(classFile, classId).instrument();
  }

  @Nullable
  private ProbeClassTransformer.Instrumented instrument() {
    String sourceFile = null;
    String sourceDebug = null;
    int at = classFile.attributesOffset;
    int attributes = classFile.readUnsignedShort(at);
    at += 2;
    for (int k = 0; k < attributes; k++) {
      int name = classFile.readUnsignedShort(at);
      int length = classFile.readInt(at + 2);
      int content = at + 6;
      if (classFile.utf8Equals(name, "SourceFile")) {
        sourceFile = classFile.utf8(classFile.readUnsignedShort(content));
      } else if (classFile.utf8Equals(name, "SourceDebugExtension")) {
        sourceDebug = classFile.decodeUtf8(content, length);
      }
      at = content + length;
    }
    if (sourceFile == null) {
      return null; // lines are reported per source file
    }
    readMethods();
    // methods with no place for a probe that the JIT compilers accept lose all their probes
    while (true) {
      try {
        return instrument(sourceFile, sourceDebug);
      } catch (ProbePlacementException e) {
        if (!skippedMethods.add(e.methodName + e.descriptor)) {
          throw e;
        }
      }
    }
  }

  private void readMethods() {
    int at = classFile.methodsOffset;
    methodCount = classFile.readUnsignedShort(at);
    at += 2;
    methodStart = new int[methodCount + 1];
    methodName = new String[methodCount];
    methodDescriptor = new String[methodCount];
    codeAttribute = new int[methodCount];
    methodCode = new MethodCode[methodCount];
    for (int m = 0; m < methodCount; m++) {
      methodStart[m] = at;
      int access = classFile.readUnsignedShort(at);
      methodName[m] = classFile.utf8(classFile.readUnsignedShort(at + 2));
      methodDescriptor[m] = classFile.utf8(classFile.readUnsignedShort(at + 4));
      int attributeCount = classFile.readUnsignedShort(at + 6);
      at += 8;
      codeAttribute[m] = -1;
      for (int k = 0; k < attributeCount; k++) {
        if (classFile.utf8Equals(classFile.readUnsignedShort(at), "Code")) {
          codeAttribute[m] = at;
        }
        at += 6 + classFile.readInt(at + 2);
      }
      if (codeAttribute[m] >= 0) {
        methodCode[m] =
            new MethodCode(
                classFile, access, methodName[m], methodDescriptor[m], codeAttribute[m] + 6);
      }
    }
    methodStart[methodCount] = at;
  }

  @Nullable
  private ProbeClassTransformer.Instrumented instrument(
      String sourceFile, @Nullable String sourceDebug) {
    ClassProbesBuilder probes = new ClassProbesBuilder();
    CodePatcher[] patched = new CodePatcher[methodCount];
    int initializer = -1;
    boolean instrumented = false;
    for (int m = 0; m < methodCount; m++) {
      if ("<clinit>".equals(methodName[m])) {
        initializer = m;
      } else {
        instrumented |= instrument(m, probes, false, patched);
      }
    }
    // initializer probes take the highest ids, so that windows can skip them
    probes.startInitializerProbes();
    if (initializer >= 0) {
      instrumented |= instrument(initializer, probes, true, patched);
    }
    if (!instrumented) {
      return null;
    }
    String className = classFile.className(classFile.thisClass);
    return new ProbeClassTransformer.Instrumented(
        classId, write(patched), className, sourceFile, sourceDebug, probes);
  }

  private boolean instrument(
      int m, ClassProbesBuilder probes, boolean initializer, CodePatcher[] patched) {
    if (methodCode[m] == null || skippedMethods.contains(methodName[m] + methodDescriptor[m])) {
      return false;
    }
    CodePatcher patcher = new CodePatcher(this, methodCode[m], classId, probes, initializer, true);
    if (!patcher.instrument()) {
      return false;
    }
    patched[m] = patcher;
    return true;
  }

  private byte[] write(CodePatcher[] patched) {
    // the Code attributes are written first, since they may add constants
    ByteArrayBuilder codes = new ByteArrayBuilder(1024);
    int[] codeFrom = new int[methodCount];
    int[] codeTo = new int[methodCount];
    for (int m = 0; m < methodCount; m++) {
      if (patched[m] != null) {
        codeFrom[m] = codes.length;
        patched[m].write(codes, classFile.readUnsignedShort(codeAttribute[m]));
        codeTo[m] = codes.length;
      }
    }

    byte[] bytes = classFile.bytes;
    ByteArrayBuilder out = new ByteArrayBuilder(bytes.length + codes.length + 512);
    out.putBytes(bytes, 0, 8);
    out.putShort(constants.constantCount());
    out.putBytes(bytes, 10, classFile.constantsEnd - 10);
    constants.writeTo(out);
    out.putBytes(
        bytes, classFile.constantsEnd, classFile.methodsOffset + 2 - classFile.constantsEnd);
    for (int m = 0; m < methodCount; m++) {
      int start = methodStart[m];
      int end = methodStart[m + 1];
      if (patched[m] == null) {
        out.putBytes(bytes, start, end - start);
      } else {
        int code = codeAttribute[m];
        int codeEnd = code + 6 + classFile.readInt(code + 2);
        out.putBytes(bytes, start, code - start);
        out.putBytes(codes.data, codeFrom[m], codeTo[m] - codeFrom[m]);
        out.putBytes(bytes, codeEnd, end - codeEnd);
      }
    }
    out.putBytes(bytes, classFile.attributesOffset, bytes.length - classFile.attributesOffset);
    return out.toByteArray();
  }

  int probesMethod() {
    if (probesMethod == 0) {
      probesMethod = constants.methodReference(RECORDER, "probes", "(I)" + PROBES_TYPE);
    }
    return probesMethod;
  }

  int recordMethod() {
    if (recordMethod == 0) {
      recordMethod = constants.methodReference(RECORDER, "record", "(II)V");
    }
    return recordMethod;
  }

  int recordInitMethod() {
    if (recordInitMethod == 0) {
      recordInitMethod = constants.methodReference(RECORDER, "recordInit", "(II)V");
    }
    return recordInitMethod;
  }

  int hitMethod() {
    if (hitMethod == 0) {
      hitMethod = constants.methodReference(RECORDER, "hit", "(" + PROBES_TYPE + "II)V");
    }
    return hitMethod;
  }

  int recordThrownMethod() {
    if (recordThrownMethod == 0) {
      recordThrownMethod = constants.methodReference(RECORDER, "recordThrown", "(II)V");
    }
    return recordThrownMethod;
  }

  int recordInitThrownMethod() {
    if (recordInitThrownMethod == 0) {
      recordInitThrownMethod = constants.methodReference(RECORDER, "recordInitThrown", "(II)V");
    }
    return recordInitThrownMethod;
  }

  int probesField() {
    if (probesField == 0) {
      probesField = constants.fieldReference(RECORDER, "PROBES", "[" + PROBES_TYPE);
    }
    return probesField;
  }

  /** The stack map frame type of a probe array. */
  int probesType() {
    if (probesType == 0) {
      probesType = StackMapFrames.object(constants.existingOrNewClass(PROBES_TYPE));
    }
    return probesType;
  }

  int throwableType() {
    if (throwableType == 0) {
      throwableType = StackMapFrames.object(constants.existingOrNewClass("java/lang/Throwable"));
    }
    return throwableType;
  }

  int thisType() {
    return StackMapFrames.object(classFile.thisClass);
  }

  int integerConstant(int value) {
    return constants.integer(value);
  }

  int stackMapTableName() {
    if (stackMapTableName == 0) {
      stackMapTableName = constants.existingOrNewUtf8("StackMapTable");
    }
    return stackMapTableName;
  }

  /** The name of a class constant of the class or added to it. */
  String className(int index) {
    return constants.className(index);
  }

  /**
   * The stack map frame type of a value with the field descriptor between {@code start} and {@code
   * end} of {@code descriptor}.
   */
  int frameType(String descriptor, int start, int end) {
    switch (descriptor.charAt(start)) {
      case 'Z':
      case 'C':
      case 'B':
      case 'S':
      case 'I':
        return StackMapFrames.INTEGER;
      case 'F':
        return StackMapFrames.FLOAT;
      case 'J':
        return StackMapFrames.LONG;
      case 'D':
        return StackMapFrames.DOUBLE;
      case 'L':
        return StackMapFrames.object(
            constants.existingOrNewClass(descriptor.substring(start + 1, end - 1)));
      default: // an array
        return StackMapFrames.object(
            constants.existingOrNewClass(descriptor.substring(start, end)));
    }
  }

  /** The end of the field descriptor that starts at {@code at} of {@code descriptor}. */
  static int typeEnd(String descriptor, int at) {
    while (descriptor.charAt(at) == '[') {
      at++;
    }
    return descriptor.charAt(at) == 'L' ? descriptor.indexOf(';', at) + 1 : at + 1;
  }
}
