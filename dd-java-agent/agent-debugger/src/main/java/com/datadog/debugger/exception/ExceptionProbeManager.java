package com.datadog.debugger.exception;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.probe.Where;
import com.datadog.debugger.util.ClassNameFiltering;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the probes used for instrumentation of exception stacktraces. */
public class ExceptionProbeManager {
  private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();
  private final Map<String, ExceptionProbe> probes = new ConcurrentHashMap<>();
  private final ClassNameFiltering classNameFiltering;

  public ExceptionProbeManager(ClassNameFiltering classNameFiltering) {
    this.classNameFiltering = classNameFiltering;
  }

  public ClassNameFiltering getClassNameFiltering() {
    return classNameFiltering;
  }

  public void createProbesForException(String fingerprint, StackTraceElement[] stackTraceElements) {
    for (StackTraceElement stackTraceElement : stackTraceElements) {
      if (stackTraceElement.isNativeMethod() || stackTraceElement.getLineNumber() < 0) {
        // Skip native methods and lines without line numbers
        // TODO log?
        continue;
      }
      if (classNameFiltering.apply(stackTraceElement.getClassName())) {
        continue;
      }
      Where where =
          Where.from(
              stackTraceElement.getClassName(),
              stackTraceElement.getMethodName(),
              null,
              String.valueOf(stackTraceElement.getLineNumber()));
      ExceptionProbe probe = createMethodProbe(this, where);
      probes.putIfAbsent(probe.getId(), probe);
    }
    fingerprints.add(fingerprint);
  }

  private static ExceptionProbe createMethodProbe(
      ExceptionProbeManager exceptionProbeManager, Where where) {
    String probeId = UUID.randomUUID().toString();
    return new ExceptionProbe(
        new ProbeId(probeId, 0), where, null, null, null, exceptionProbeManager);
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.contains(fingerprint);
  }

  public Collection<ExceptionProbe> getProbes() {
    return probes.values();
  }

  public boolean shouldCaptureException(String fingerprint) {
    return fingerprints.contains(fingerprint);
  }
}
