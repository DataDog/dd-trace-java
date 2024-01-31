package com.datadog.debugger.exception;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Manages the probes used for instrumentation of exception stacktraces. */
public class ExceptionProbeManager {
  private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();

  public void instrument(String fingerprint, StackTraceElement[] stackTraceElements) {
    fingerprints.add(fingerprint);
    // TODO instrument each frame, filtering out thirdparty code
  }

  public boolean isAlreadyInstrumented(String fingerprint) {
    return fingerprints.contains(fingerprint);
  }
}
