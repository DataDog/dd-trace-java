package com.datadog.debugger.instrumentation;

import com.datadog.debugger.util.ClassFileLines;
import datadog.trace.util.Strings;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
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

  public String getMethodName() {
    return methodNode.name;
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

  public String getSignature() {
    return methodNode.desc != null ? Types.descriptorToSignature(methodNode.desc) : null;
  }

  public String getSourceFileName() {
    return classNode.sourceFile;
  }

  public String getTypeName() {
    return Strings.getClassName(classNode.name);
  }

  public List<Integer> getLineNumbers() {
    List<Integer> lines = new ArrayList<>();
    AbstractInsnNode current = methodNode.instructions.getFirst();
    while (current != null) {
      if (current.getType() == AbstractInsnNode.LINE) {
        LineNumberNode lineNode = (LineNumberNode) current;
        lines.add(lineNode.line);
      }
      current = current.getNext();
    }
    return lines;
  }

  @Override
  public String toString() {
    return String.format(
        "MethodInfo{classNode=%s, methodNode=%s}", classNode.name, methodNode.desc);
  }
}
