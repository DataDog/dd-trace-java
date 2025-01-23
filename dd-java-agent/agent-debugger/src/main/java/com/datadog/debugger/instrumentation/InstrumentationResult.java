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
  private final String signature;

  public static class Factory {
    public static InstrumentationResult blocked(String className) {
      return new InstrumentationResult(Status.BLOCKED, className, null);
    }

    public static InstrumentationResult blocked(
        String className, List<ProbeDefinition> definitions, DiagnosticMessage... messages) {
      Map<ProbeId, List<DiagnosticMessage>> diagnostics = new HashMap<>();
      definitions.forEach(
          probeDefinition ->
              diagnostics.put(probeDefinition.getProbeId(), Arrays.asList(messages)));
      return new InstrumentationResult(Status.BLOCKED, diagnostics, null, className, null);
    }
  }

  public InstrumentationResult(
      Status status, Map<ProbeId, List<DiagnosticMessage>> diagnostics, MethodInfo methodInfo) {
    this.status = status;
    this.diagnostics = diagnostics;
    this.sourceFileName = methodInfo.getSourceFileName();
    this.typeName = methodInfo.getTypeName();
    this.methodName = methodInfo.getMethodName();
    this.methodStart = methodInfo.getMethodStart();
    this.signature = methodInfo.getSignature();
  }

  public InstrumentationResult(Status status, String className, String methodName) {
    this(status, null, className, className, methodName);
  }

  public InstrumentationResult(
      Status status,
      Map<ProbeId, List<DiagnosticMessage>> diagnostics,
      String sourceFileName,
      String className,
      String methodName) {
    this.status = status;
    this.diagnostics = diagnostics;
    this.sourceFileName = sourceFileName;
    this.typeName = className;
    this.methodName = methodName;
    this.methodStart = -1;
    this.signature = null;
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

  public String getMethodSignature() {
    return signature;
  }

  @Generated
  @Override
  public String toString() {
    return String.format(
        "InstrumentationResult{typeName='%s', methodName='%s', methodStart=%d, signature='%s',"
            + " sourceFileName='%s', status=%s. diagnostics=%s}",
        typeName, methodName, methodStart, signature, sourceFileName, status, diagnostics);
  }
}
