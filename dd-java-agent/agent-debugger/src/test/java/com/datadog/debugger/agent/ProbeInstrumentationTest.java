package com.datadog.debugger.agent;

import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DiagnosticMessage;
import datadog.trace.bootstrap.debugger.Snapshot;
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

  protected static class MockSink implements DebuggerContext.Sink {

    private final List<DiagnosticMessage> currentDiagnostics = new ArrayList<>();

    @Override
    public void addSnapshot(Snapshot snapshot) {}

    @Override
    public void addDiagnostics(String probeId, List<DiagnosticMessage> messages) {
      for (DiagnosticMessage msg : messages) {
        System.out.println(msg);
      }
      currentDiagnostics.addAll(messages);
    }

    public List<DiagnosticMessage> getCurrentDiagnostics() {
      return currentDiagnostics;
    }
  }
}
