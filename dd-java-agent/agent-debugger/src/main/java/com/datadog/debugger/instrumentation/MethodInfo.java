package com.datadog.debugger.instrumentation;

import com.datadog.debugger.util.ClassFileLines;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Data class to store all information related to a method (class, classloader, lines) */
public class MethodInfo {
  private final ClassLoader classLoader;
  private final ClassNode classNode;
  private final MethodNode methodNode;
  private final ClassFileLines classFileLines;

  public MethodInfo(
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

  public int getMethodStart() {
    return classFileLines != null ? classFileLines.getMethodStart(methodNode) : -1;
  }
}
