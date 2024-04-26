package com.datadog.debugger.instrumentation;

import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;

import java.util.List;

// Class that stores information about all probe definition applied on the same code location
public class ToInstrumentInfo {
  public final ProbeDefinition definition;
  public final List<ProbeId> probeIds;
  public final boolean atLeastOneProbeHasCondition;

  public ToInstrumentInfo(ProbeDefinition definition, List<ProbeId> probeIds, boolean atLeastOneProbeHasCondition) {
    this.definition = definition;
    this.probeIds = probeIds;
    this.atLeastOneProbeHasCondition = atLeastOneProbeHasCondition;
  }
}
