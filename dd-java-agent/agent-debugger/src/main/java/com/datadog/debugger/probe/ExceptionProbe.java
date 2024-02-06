package com.datadog.debugger.probe;

import com.datadog.debugger.el.ProbeCondition;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.ProbeId;

public class ExceptionProbe extends LogProbe {
  private final String fingerprint;

  public ExceptionProbe(
      ProbeId probeId,
      Where where,
      ProbeCondition probeCondition,
      Capture capture,
      Sampling sampling,
      String fingerprint) {
    super(
        LANGUAGE,
        probeId,
        null,
        where,
        MethodLocation.EXIT,
        null,
        null,
        true,
        probeCondition,
        capture,
        sampling);
    this.fingerprint = fingerprint;
  }
}
