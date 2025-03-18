package com.datadog.debugger.util;

import java.nio.charset.StandardCharsets;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Helper class for extracting information of a class file */
public class ClassFileHelper {
  private static final int CONSTANT_POOL_COUNT_OFFSET = 8;
  private static final int CONSTANT_POOL_BASE_OFFSET = 10;

  public static String extractSourceFile(byte[] classFileBuffer) {
    return extractSourceFileOffsetVersion(classFileBuffer);
  }

  // Version using ASM library
  private static String extractSourceFileASM(byte[] classFileBuffer) {
    ClassReader classReader = new ClassReader(classFileBuffer);
    ClassNode classNode = new ClassNode();
    classReader.accept(classNode, ClassReader.SKIP_FRAMES);
    return classNode.sourceFile;
  }

  public static String removeExtension(String fileName) {
    int idx = fileName.lastIndexOf('.');
    if (idx > -1) {
      fileName = fileName.substring(0, idx);
    }
    return fileName;
  }

  public static String normalizeFilePath(String filePath) {
    filePath = filePath.replace('/', '.');
    return filePath;
  }

  public static String stripPackagePath(String classPath) {
    int idx = classPath.lastIndexOf('/');
    if (idx != -1) {
      classPath = classPath.substring(idx + 1);
    }
    return classPath;
  }

  // Based on JVM spec: https://docs.oracle.com/javase/specs/jvms/se23/html/jvms-4.html
  // Extracts the SourceFile attribute from a Java class file byte array with minimal parsing.
  // This method is based on the JVM spec and does not use any external libraries.
  // We are scanning the constant pool to keep file offsets for later fetching of the SourceFile
  // attribute value. As the constant pool is a variable length structure, we need to scan them
  // and based on the tag, we can calculate the length of the entry to skip to the next one.
  private static String extractSourceFileOffsetVersion(byte[] classFileBytes) {
    // Quick validation of minimum class file size and magic number
    if (classFileBytes == null
        || classFileBytes.length < 10
        || classFileBytes[0] != (byte) 0xCA
        || classFileBytes[1] != (byte) 0xFE
        || classFileBytes[2] != (byte) 0xBA
        || classFileBytes[3] != (byte) 0xBE) {
      return null;
    }
    int constantPoolCount = readUnsignedShort(classFileBytes, CONSTANT_POOL_COUNT_OFFSET);
    int[] constantPoolOffsets = new int[constantPoolCount];
    int currentOffset = CONSTANT_POOL_BASE_OFFSET;
    // based on the JVM spec, constant pool starts from index 1 until constantPoolCount - 1
    for (int i = 0; i < constantPoolCount - 1; i++) {
      constantPoolOffsets[i] = currentOffset;
      int tag = classFileBytes[constantPoolOffsets[i]];
      switch (tag) {
        case 1: // CONSTANT_Utf8
          int length = readUnsignedShort(classFileBytes, constantPoolOffsets[i] + 1);
          currentOffset += 3 + length;
          break;
        case 7: // CONSTANT_Class
        case 8: // CONSTANT_String
        case 16: // CONSTANT_MethodType
        case 19: // CONSTANT_Module
        case 20: // CONSTANT_Package
          currentOffset += 3;
          break;
        case 15: // CONSTANT_MethodHandle
          currentOffset += 4;
          break;
        case 3: // CONSTANT_Integer
        case 4: // CONSTANT_Float
        case 9: // CONSTANT_Fieldref
        case 10: // CONSTANT_Methodref
        case 11: // CONSTANT_InterfaceMethodref
        case 12: // CONSTANT_NameAndType
        case 17: // CONSTANT_Dynamic
        case 18: // CONSTANT_InvokeDynamic
          currentOffset += 5;
          break;
        case 5: // CONSTANT_Long
        case 6: // CONSTANT_Double
          currentOffset += 9;
          i++; // Double slot
          break;
        default:
          throw new IllegalArgumentException("Unknown constant pool tag: " + tag);
      }
    }
    currentOffset += 2; // Skip access flags
    currentOffset += 2; // Skip this class
    currentOffset += 2; // Skip super class
    int interfacesCount = readUnsignedShort(classFileBytes, currentOffset);
    currentOffset += 2 + interfacesCount * 2; // Skip interfaces
    // skip fields
    currentOffset = skipFieldsOrMethods(classFileBytes, currentOffset);
    // skip Methods
    currentOffset = skipFieldsOrMethods(classFileBytes, currentOffset);
    int attributesCount = readUnsignedShort(classFileBytes, currentOffset);
    currentOffset += 2; // Skip attributes count
    for (int i = 0; i < attributesCount; i++) {
      int attributeNameIndex = readUnsignedShort(classFileBytes, currentOffset);
      currentOffset += 2; // Skip attribute name index
      int attributeLength = (int) readUnsignedInt(classFileBytes, currentOffset);
      currentOffset += 4; // Skip attribute length
      if (attributeNameIndex == 0) {
        continue;
      }
      // read attribute name
      int utf8Offset = constantPoolOffsets[attributeNameIndex - 1];
      int utf8Len = readUnsignedShort(classFileBytes, utf8Offset + 1);
      String utf8 = new String(classFileBytes, utf8Offset + 3, utf8Len, StandardCharsets.UTF_8);
      if ("SourceFile".equals(utf8)) {
        // read SourceFile attribute
        int sourceFileIndex = readUnsignedShort(classFileBytes, currentOffset);
        int sourceFileOffset = constantPoolOffsets[sourceFileIndex - 1];
        int sourceFileLen = readUnsignedShort(classFileBytes, sourceFileOffset + 1);
        return new String(
            classFileBytes, sourceFileOffset + 3, sourceFileLen, StandardCharsets.UTF_8);
      }
      currentOffset += attributeLength; // Skip attribute data
    }
    return null;
  }

  private static int skipFieldsOrMethods(byte[] classFileBytes, int currentOffset) {
    int fieldsCount = readUnsignedShort(classFileBytes, currentOffset);
    currentOffset += 2; // Skip count
    for (int i = 0; i < fieldsCount; i++) {
      currentOffset += 6; // Skip access flags, name index, descriptor index
      int attributesCount = readUnsignedShort(classFileBytes, currentOffset);
      currentOffset += 2; // Skip attributes count
      for (int j = 0; j < attributesCount; j++) {
        currentOffset += 2; // Skip attribute name index
        int attributeLength = (int) readUnsignedInt(classFileBytes, currentOffset);
        currentOffset += 4 + attributeLength; // Skip attribute length and data
      }
    }
    return currentOffset;
  }

  // read unsigned short from byte array
  private static int readUnsignedShort(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
  }

  // read unsigned int from byte array
  private static long readUnsignedInt(byte[] bytes, int offset) {
    return ((long) (bytes[offset] & 0xFF) << 24)
        + ((bytes[offset + 1] & 0xFF) << 16)
        + ((bytes[offset + 2] & 0xFF) << 8)
        + (bytes[offset + 3] & 0xFF);
  }
}
