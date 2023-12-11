package com.datadog.debugger.agent;

import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;

public class ProbeInstrumentationTest {
  protected static final String SERVICE_NAME = "service-name";

  protected Instrumentation instr = ByteBuddyAgent.install();
  protected ClassFileTransformer currentTransformer;
  protected MockSink mockSink;
  protected ProbeStatusSink probeStatusSink;

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
  }

  protected static class MockSink extends DebuggerSink {

    private final List<Snapshot> snapshots = new ArrayList<>();

    public MockSink(Config config, ProbeStatusSink probeStatusSink) {
      super(config, probeStatusSink);
    }

    @Override
    public void addSnapshot(Snapshot snapshot) {
      snapshots.add(snapshot);
    }

    @Override
    public void skipSnapshot(String probeId, DebuggerContext.SkipCause cause) {}

    public List<Snapshot> getSnapshots() {
      return snapshots;
    }
  }
}
