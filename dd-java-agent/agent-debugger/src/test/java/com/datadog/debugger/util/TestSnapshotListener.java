package com.datadog.debugger.util;

import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import java.util.ArrayList;
import java.util.List;

public class TestSnapshotListener extends DebuggerSink {
  public boolean skipped;
  public DebuggerContext.SkipCause cause;
  public List<Snapshot> snapshots = new ArrayList<>();

  public TestSnapshotListener(Config config, ProbeStatusSink probeStatusSink) {
    super(config, probeStatusSink);
  }

  @Override
  public void skipSnapshot(String probeId, DebuggerContext.SkipCause cause) {
    skipped = true;
    this.cause = cause;
  }

  @Override
  public void addSnapshot(Snapshot snapshot) {
    snapshots.add(snapshot);
  }

  @Override
  public void addHighRateSnapshot(Snapshot snapshot) {
    snapshots.add(snapshot);
  }
}
