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
}
