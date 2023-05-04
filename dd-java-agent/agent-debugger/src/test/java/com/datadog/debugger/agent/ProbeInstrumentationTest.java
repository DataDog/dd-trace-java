package com.datadog.debugger.agent;

import com.datadog.debugger.instrumentation.DiagnosticMessage;
import com.datadog.debugger.sink.Sink;
import com.datadog.debugger.sink.Snapshot;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.ProbeId;
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

  @AfterEach
  public void after() {
    if (currentTransformer != null) {
      instr.removeTransformer(currentTransformer);
    }
  }

  protected static class MockSink implements Sink {

    private final List<DiagnosticMessage> currentDiagnostics = new ArrayList<>();
    private final List<Snapshot> snapshots = new ArrayList<>();

    @Override
    public void addSnapshot(Snapshot snapshot) {
      snapshots.add(snapshot);
    }

    @Override
    public void skipSnapshot(String probeId, DebuggerContext.SkipCause cause) {}

    @Override
    public void addDiagnostics(ProbeId probeId, List<DiagnosticMessage> messages) {
      for (DiagnosticMessage msg : messages) {
        System.out.println(msg);
      }
      currentDiagnostics.addAll(messages);
    }

    public List<DiagnosticMessage> getCurrentDiagnostics() {
      return currentDiagnostics;
    }

    public List<Snapshot> getSnapshots() {
      return snapshots;
    }
  }
}
