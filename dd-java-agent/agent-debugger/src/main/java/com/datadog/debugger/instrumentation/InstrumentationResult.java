package com.datadog.debugger.instrumentation;

import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import java.util.Arrays;
import java.util.List;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Stores status instrumentation results */
public class InstrumentationResult {
  public enum Status {
    INSTALLED,
    BLOCKED,
    ERROR
  }

  private final Status status;
  private final List<DiagnosticMessage> diagnostics;
  private String typeName;
  private String methodName;

  public static class Factory {
    public static InstrumentationResult blocked(String className) {
      return new InstrumentationResult(Status.BLOCKED, null, className, null);
    }

    public static InstrumentationResult blocked(String className, DiagnosticMessage... messages) {
      return new InstrumentationResult(Status.BLOCKED, Arrays.asList(messages), className, null);
    }

    public static InstrumentationResult installed(
        ClassNode classNode, MethodNode methodNode, List<DiagnosticMessage> diagnostics) {
      return new InstrumentationResult(Status.INSTALLED, diagnostics, classNode, methodNode);
    }

    public static InstrumentationResult error(
        ClassNode classNode, MethodNode methodNode, List<DiagnosticMessage> diagnostics) {
      return new InstrumentationResult(Status.ERROR, diagnostics, classNode, methodNode);
    }
  }

  public InstrumentationResult(
      Status status,
      List<DiagnosticMessage> diagnostics,
      ClassNode classNode,
      MethodNode methodNode) {
    this(status, diagnostics, classNode.name.replace('/', '.'), methodNode.name);
  }

  public InstrumentationResult(
      Status status, List<DiagnosticMessage> diagnostics, String className, String methodName) {
    this.status = status;
    this.diagnostics = diagnostics;
    this.typeName = className;
    this.methodName = methodName;
  }

  public boolean isBlocked() {
    return status == Status.BLOCKED;
  }

  public boolean isInstalled() {
    return status == Status.INSTALLED;
  }

  public List<DiagnosticMessage> getDiagnostics() {
    return diagnostics;
  }

  public String getTypeName() {
    return typeName;
  }

  public String getMethodName() {
    return methodName;
  }
}
