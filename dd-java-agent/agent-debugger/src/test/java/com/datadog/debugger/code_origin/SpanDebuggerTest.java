package com.datadog.debugger.code_origin;

import static datadog.trace.bootstrap.debugger.CapturedContext.EMPTY_CAPTURING_CONTEXT;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.agent.ConfigurationUpdater;
import com.datadog.debugger.agent.DebuggerAgentHelper;
import com.datadog.debugger.probe.LogProbe.LogStatus;
import com.datadog.debugger.probe.SpanDebuggerProbe;
import com.datadog.debugger.sink.ProbeStatusSink;
import com.datadog.debugger.snapshot.DefaultSpanDebugger;
import com.datadog.debugger.snapshot.SpanDebug;
import com.datadog.debugger.util.ClassNameFiltering;
import com.datadog.debugger.util.TestSnapshotListener;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.MethodLocation;
import datadog.trace.bootstrap.debugger.spanorigin.SpanOriginInfo;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.core.CoreTracer;
import datadog.trace.test.util.DDSpecification;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SpanDebuggerTest extends DDSpecification {
  private ClassNameFiltering classNameFiltering;

  private ConfigurationUpdater configurationUpdater;

  private int count = 0;

  private TestSnapshotListener listener;

  private DefaultSpanDebugger spanDebugger;
  private TracerAPI tracerAPI;

  @BeforeEach
  public void setUp() {
    injectSysConfig("dd.trace.span.origin.enabled", "true");
    AgentTracer.registerIfAbsent(CoreTracer.builder().build());
    tracerAPI = AgentTracer.get();
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
                    "datadog",
                    "com.datadog.debugger.probe",
                    "com.datadog.debugger.snapshot")));
    spanDebugger = new DefaultSpanDebugger(configurationUpdater, classNameFiltering);
    spanDebugger.taskScheduler(
        new AgentTaskScheduler(TASK_SCHEDULER) {
          @Override
          public void execute(Runnable target) {
            target.run();
          }
        });
    DebuggerContext.initSpanDebugger(spanDebugger);

    listener = new TestSnapshotListener(createConfig(), mock(ProbeStatusSink.class));
    DebuggerAgentHelper.injectSink(listener);
  }

  @Test
  public void fingerprinting() throws InterruptedException {

    AgentSpan span1 = createProbePath1();
    SpanDebug.enableDebug(span1, SpanDebug.ALL_FRAMES);
    AgentSpan span2 = createProbePath1a();
    SpanDebug.enableDebug(span2, 31);
    AgentSpan span3 = createProbePath1();

    SpanDebuggerProbe[] probes =
        spanDebugger.probeManager().getProbes().toArray(new SpanDebuggerProbe[0]);

    assertEquals(2, probes.length);
    SpanDebuggerProbe entryProbe;
    SpanDebuggerProbe exitProbe;
    if (probes[0].isEntrySpanProbe()) {
      entryProbe = probes[0];
      exitProbe = probes[1];
    } else {
      entryProbe = probes[1];
      exitProbe = probes[0];
    }
    assertTrue(entryProbe.isEntrySpanProbe());
    assertFalse(exitProbe.isEntrySpanProbe());
    invoke(span1, entryProbe);

    assertTrue(
        span1.getTags().containsKey(DDTags.DD_ENTRY_LOCATION_FILE),
        span1.getTags().keySet().toString());
    assertFalse(
        span1.getTags().containsKey(DDTags.DD_ENTRY_LOCATION_SNAPSHOT_ID),
        span1.getTags().keySet().toString());

    invoke(span2, exitProbe);
    assertTrue(
        span2.getTags().containsKey(String.format(DDTags.DD_EXIT_LOCATION_FILE, 0)),
        span2.getTags().keySet().toString());
    assertTrue(
        span2.getTags().containsKey(DDTags.DD_EXIT_LOCATION_SNAPSHOT_ID),
        span2.getTags().keySet().toString());

    invoke(span3, entryProbe);

    assertTrue(
        span3.getTags().containsKey(DDTags.DD_ENTRY_LOCATION_FILE),
        span3.getTags().keySet().toString());
    assertFalse(
        span3.getTags().containsKey(DDTags.DD_ENTRY_LOCATION_SNAPSHOT_ID),
        span3.getTags().keySet().toString());
  }

  private void invoke(AgentSpan span1, SpanDebuggerProbe probe) {
    tracerAPI.activateSpan(span1, ScopeSource.MANUAL);
    probe.evaluate(EMPTY_CAPTURING_CONTEXT, LogStatus.EMPTY_LOG_STATUS, MethodLocation.EXIT);
    probe.commit(EMPTY_CAPTURING_CONTEXT, EMPTY_CAPTURING_CONTEXT, emptyList());
  }

  private AgentSpan createProbePath1() {
    return createProbePath2();
  }

  private AgentSpan createProbePath1a() {
    return createProbePath3();
  }

  private AgentSpan createProbePath2() {
    return createProbePath3();
  }

  private AgentSpan createProbePath3() {
    AgentSpan span = tracerAPI.startSpan("span debugger test", "span" + (++count));
    try {

      if (count % 2 == 1) {
        SpanOriginInfo.entry(span, getClass().getDeclaredMethod("createProbePath3"));
      } else {
        SpanOriginInfo.exit(span);
      }

      return span;
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
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
