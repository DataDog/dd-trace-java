package com.datadog.debugger.agent;

import com.datadog.debugger.sink.DebuggerSink;

public class DebuggerAgentHelper {
  public static void injectSink(DebuggerSink sink) {
    DebuggerAgent.initSink(sink);
  }

  public static void injectSerializer(JsonSnapshotSerializer snapshotSerializer) {
    DebuggerAgent.initSnapshotSerializer(snapshotSerializer);
  }
}
