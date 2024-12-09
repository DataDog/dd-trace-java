package com.datadog.debugger.instrumentation;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores status instrumentation results */
public class InstrumentationResult {
  public enum Status {
    INSTALLED,
    BLOCKED,
    ERROR
  }

  private final Status status;
  private final Map<ProbeId, List<DiagnosticMessage>> diagnostics;
  private final String sourceFileName;
  private final String typeName;
  private final String methodName;
  private final int methodStart;

  public static class Factory {
    public static InstrumentationResult blocked(String className) {
      return new InstrumentationResult(Status.BLOCKED, null, className, null);
    }

    public static InstrumentationResult blocked(
        String className, List<ProbeDefinition> definitions, DiagnosticMessage... messages) {
      Map<ProbeId, List<DiagnosticMessage>> diagnostics = new HashMap<>();
      definitions.forEach(
          probeDefinition ->
              diagnostics.put(probeDefinition.getProbeId(), Arrays.asList(messages)));
      return new InstrumentationResult(Status.BLOCKED, diagnostics, className, null);
    }
  }

  public InstrumentationResult(
      Status status, Map<ProbeId, List<DiagnosticMessage>> diagnostics, MethodInfo methodInfo) {
    this(
        status,
        diagnostics,
        methodInfo.getClassNode().sourceFile,
        methodInfo.getClassNode().name.replace('/', '.'),
        methodInfo.getMethodNode().name,
        methodInfo.getMethodStart());
  }

  public InstrumentationResult(
      Status status,
      Map<ProbeId, List<DiagnosticMessage>> diagnostics,
      String className,
      String methodName) {
    this.status = status;
    this.diagnostics = diagnostics;
    this.typeName = className;
    this.methodName = methodName;
    this.methodStart = -1;
    this.sourceFileName = null;
  }

  public InstrumentationResult(
      Status status,
      Map<ProbeId, List<DiagnosticMessage>> diagnostics,
      String sourceFileName,
      String className,
      String methodName,
      int methodStart) {
    this.status = status;
    this.diagnostics = diagnostics;
    this.sourceFileName = sourceFileName;
    this.typeName = className;
    this.methodName = methodName;
    this.methodStart = methodStart;
  }

  public boolean isError() {
    return status == Status.ERROR;
  }

  public boolean isBlocked() {
    return status == Status.BLOCKED;
  }

  public boolean isInstalled() {
    return status == Status.INSTALLED;
  }

  public Map<ProbeId, List<DiagnosticMessage>> getDiagnostics() {
    return diagnostics;
  }

  public String getTypeName() {
    return typeName;
  }

  public String getMethodName() {
    return methodName;
  }

  public int getMethodStart() {
    return methodStart;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  @Generated
  @Override
  public String toString() {
    return "InstrumentationResult{"
        + "status="
        + status
        + ", diagnostics="
        + diagnostics
        + ", typeName='"
        + typeName
        + '\''
        + ", methodName='"
        + methodName
        + '\''
        + '}';
  }
}
