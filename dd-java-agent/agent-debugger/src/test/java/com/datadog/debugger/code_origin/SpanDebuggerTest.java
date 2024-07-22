package com.datadog.debugger.code_origin;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.snapshot.DefaultSpanDebugger;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.api.Config;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SpanDebuggerTest {
  private ClassNameFiltering classNameFiltering;

  private ConfigurationUpdater configurationUpdater;

  private TestSnapshotListener listener;

  private DefaultSpanDebugger spanDebugger;

  @BeforeEach
  public void setUp() {
    configurationUpdater = mock(ConfigurationUpdater.class);
    classNameFiltering =
        new ClassNameFiltering(
            new HashSet<>(
                Arrays.asList(
                    "sun",
                    "org.junit",
                    "java.",
                    "org.gradle",
                    "com.sun",
                    "worker.org.gradle",
                    "com.datadog.debugger.snapshot")));
    spanDebugger = new DefaultSpanDebugger(configurationUpdater, classNameFiltering);
    spanDebugger.taskScheduler(
        new AgentTaskScheduler(TASK_SCHEDULER) {
          @Override
          public void execute(Runnable target) {
            target.run();
          }
        });

    listener = new TestSnapshotListener(createConfig(), mock(ProbeStatusSink.class));
    DebuggerAgentHelper.injectSink(listener);
  }

  @Test
  public void fingerprinting() throws InterruptedException {
    String probeId1 = createProbePath1();
    String probeId2 = createProbePath1a();

    assertEquals(probeId1, probeId2);

    assertEquals(1, spanDebugger.probeManager().getProbes().size());
  }

  private String createProbePath1() {
    return createProbePath2();
  }

  private String createProbePath1a() {
    return createProbePath3();
  }

  private String createProbePath2() {
    return createProbePath3();
  }

  private String createProbePath3() {
    return spanDebugger.captureSnapshot(null);
  }

  public static Config createConfig() {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    when(config.isDebuggerVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    when(config.getDebuggerUploadBatchSize()).thenReturn(100);
    return config;
  }
}
