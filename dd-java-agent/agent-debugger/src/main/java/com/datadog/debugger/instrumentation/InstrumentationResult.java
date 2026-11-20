package com.datadog.debugger.instrumentation;

import com.datadog.debugger.agent.Generated;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.util.Strings;
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

  private final Map<ProbeId, Status> probeStatuses;
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
      definitions.forEach(probeDefinition ->
          diagnostics.put(probeDefinition.getProbeId(), Arrays.asList(messages)));
      return new InstrumentationResult(Status.BLOCKED, diagnostics, null, className, null);
    }
  }

  /**
   * Builds a result for a method instrumented with several probes. The method is considered
   * installed if at least one probe is installed, while each probe keeps its own status.
   */
  public InstrumentationResult(
      Map<ProbeId, Status> probeStatuses,
      Map<ProbeId, List<DiagnosticMessage>> diagnostics,
      MethodInfo methodInfo) {
    this.probeStatuses = probeStatuses;
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
    Map<ProbeId, Status> statuses = new HashMap<>();
    if (diagnostics != null) {
      diagnostics.keySet().forEach(probeId -> statuses.put(probeId, status));
    }
    this.probeStatuses = statuses;
    this.diagnostics = diagnostics;
    this.sourceFileName = sourceFileName;
    this.typeName = className;
    this.methodName = methodName;
    this.methodStart = -1;
    this.signature = null;
  }

  public boolean isAtLeastOneInstalled() {
    return probeStatuses.containsValue(Status.INSTALLED);
  }

  /** Returns the status of the given probe, or null if not tracked */
  private Status getStatus(ProbeId probeId) {
    return probeStatuses.get(probeId);
  }

  public boolean isError(ProbeId probeId) {
    return getStatus(probeId) == Status.ERROR;
  }

  public boolean isBlocked(ProbeId probeId) {
    return getStatus(probeId) == Status.BLOCKED;
  }

  public boolean isInstalled(ProbeId probeId) {
    return getStatus(probeId) == Status.INSTALLED;
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
    return Strings.format(
        "InstrumentationResult{typeName='%s', methodName='%s', methodStart=%d, signature='%s',"
            + " sourceFileName='%s', probeStatuses=%s, diagnostics=%s}",
        typeName, methodName, methodStart, signature, sourceFileName, probeStatuses, diagnostics);
  }
}
