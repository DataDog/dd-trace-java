package com.datadog.debugger.util;

import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.el.ProbeCondition;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.Where;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.el.DebuggerScript;
import java.lang.reflect.ParameterizedType;
import java.util.Map;

/** Helper for creating Moshi instances with the right adapters depending on the context */
public class MoshiHelper {

  public static Moshi createMoshiConfig() {
    return createMoshiConfigBuilder().build();
  }

  public static Moshi.Builder createMoshiConfigBuilder() {
    ProbeCondition.ProbeConditionJsonAdapter probeConditionJsonAdapter =
        new ProbeCondition.ProbeConditionJsonAdapter();
    return new Moshi.Builder()
        .add(ProbeCondition.class, probeConditionJsonAdapter)
        .add(DebuggerScript.class, probeConditionJsonAdapter)
        .add(ValueScript.class, new ValueScript.ValueScriptAdapter())
        .add(LogProbe.Segment.class, new LogProbe.Segment.SegmentJsonAdapter())
        .add(Where.SourceLine[].class, new Where.SourceLineAdapter())
        .add(ProbeDefinition.Tag[].class, new ProbeDefinition.TagAdapter());
  }

  public static Moshi createMoshiSnapshot() {
    return new Moshi.Builder()
        .add(new MoshiSnapshotHelper.SnapshotJsonFactory())
        .add(
            DebuggerScript.class,
            new ProbeCondition.ProbeConditionJsonAdapter()) // ProbeDetails in Snapshot
        .build();
  }

  public static Moshi createMoshiProbeStatus() {
    return new Moshi.Builder().add(new ProbeStatus.DiagnosticsFactory()).build();
  }

  public static JsonAdapter<Map<String, Object>> createGenericAdapter() {
    ParameterizedType type = Types.newParameterizedType(Map.class, String.class, Object.class);
    return new Moshi.Builder().build().adapter(type);
  }

  public static Moshi createMoshiSymbol() {
    return new Moshi.Builder().build();
  }

  public static Moshi createMoshiWatches() {
    return new Moshi.Builder().add(ValueScript.class, new ValueScript.ValueScriptAdapter()).build();
  }
}
