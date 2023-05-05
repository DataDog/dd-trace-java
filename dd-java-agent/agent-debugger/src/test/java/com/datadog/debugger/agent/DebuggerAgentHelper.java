package com.datadog.debugger.agent;

import com.datadog.debugger.sink.Sink;

public class DebuggerAgentHelper {
  public static void injectSink(Sink sink) {
    DebuggerAgent.initSink(sink);
  }

  public static void injectSerializer(JsonSnapshotSerializer snapshotSerializer) {
    DebuggerAgent.initSnapshotSerializer(snapshotSerializer);
  }
}
