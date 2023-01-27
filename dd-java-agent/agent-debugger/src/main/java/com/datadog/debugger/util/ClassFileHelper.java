package com.datadog.debugger.util;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Helper class for extracting information of a class file */
public class ClassFileHelper {
  public static String extractSourceFile(byte[] classFileBuffer) {
    // TODO maybe by scanning the byte array directly we can avoid doing an expensive parsing
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
}
