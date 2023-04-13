package com.datadog.debugger.instrumentation;

import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class InstrumentationContext {
  private final ClassLoader classLoader;
  private final ClassNode classNode;
  private final MethodNode methodNode;
  private final List<DiagnosticMessage> diagnostics;

  public InstrumentationContext(
      ClassLoader classLoader,
      ClassNode classNode,
      MethodNode methodNode,
      List<DiagnosticMessage> diagnostics) {
    this.classLoader = classLoader;
    this.classNode = classNode;
    this.methodNode = methodNode;
    this.diagnostics = diagnostics;
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

  public List<DiagnosticMessage> getDiagnostics() {
    return diagnostics;
  }
}
