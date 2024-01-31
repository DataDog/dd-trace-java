package com.datadog.debugger.instrumentation;

import com.datadog.debugger.util.ClassFileLines;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Data class to store all information related to a classfile */
public class ClassFileInfo {
  private final ClassLoader classLoader;
  private final ClassNode classNode;
  private final MethodNode methodNode;
  private final ClassFileLines classFileLines;

  public ClassFileInfo(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      ClassFileLines classFileLines) {
    this.classLoader = classLoader;
    this.classNode = classNode;
    this.methodNode = methodNode;
    this.classFileLines = classFileLines;
  }

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  public ClassNode getClassNode() {
    return classNode;
  }

  public MethodNode getMethodNode() {
    return methodNode;
  }

  public ClassFileLines getClassFileLines() {
    return classFileLines;
  }
}
