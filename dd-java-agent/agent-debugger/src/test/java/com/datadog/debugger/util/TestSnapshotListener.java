package com.datadog.debugger.util;

import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Config;
import datadog.trace.api.debugger.DebuggerMetricCollector;
import java.util.ArrayList;
import java.util.List;

public class TestSnapshotListener extends DebuggerSink {
  public boolean skipped;
  public DebuggerMetricCollector.SkippedReason reason;
  public List<Snapshot> snapshots = new ArrayList<>();

  public TestSnapshotListener(Config config, ProbeStatusSink probeStatusSink) {
    super(config, probeStatusSink);
  }

  @Override
  public void skipSnapshot(String probeId, DebuggerMetricCollector.SkippedReason reason) {
    skipped = true;
    this.reason = reason;
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
