package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.sink.DebuggerSink;
import com.datadog.debugger.sink.ProbeStatusSink;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerSpan;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.joor.Reflect;
import org.junit.jupiter.api.Test;

public class SpanProbeInstrumentationTest extends ProbeInstrumentationTest {
  private static final ProbeId SPAN_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final String SPAN_PROBEID_TAG =
      "debugger.probeid:beae1807-f3b0-4ea8-a74f-826790c5e6f8";

  @Test
  public void methodSimpleSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer = installSingleSpan(CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(1, tracer.spans.size());
    MockSpan span = tracer.spans.get(0);
    assertEquals(CLASS_NAME + ".main", span.resourceName);
    assertTrue(span.isFinished());
    assertArrayEquals(new String[] {SPAN_PROBEID_TAG}, span.tags);
    probeStatusSink.addEmitting(eq(SPAN_ID));
  }

  @Test
  public void methodSimpleSpanWithPackage() throws IOException, URISyntaxException {
    final String CLASS_NAME = "com.datadog.debugger.CapturedSnapshot10";
    final String SIMPLE_CLASS_NAME = CLASS_NAME.substring(CLASS_NAME.lastIndexOf('.') + 1);
    MockTracer tracer = installSingleSpan(CLASS_NAME, "main", "int (java.lang.String)");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(1764, result);
    assertEquals(1, tracer.spans.size());
    assertEquals(SIMPLE_CLASS_NAME + ".main", tracer.spans.get(0).resourceName);
    assertTrue(tracer.spans.get(0).isFinished());
  }

  @Test
  public void methodSimpleSpanWithTags() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer =
        installSingleSpan(CLASS_NAME, "main", "int (java.lang.String)", "tag1:foo1", "tag2:foo2");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(1, tracer.spans.size());
    MockSpan span = tracer.spans.get(0);
    assertEquals(CLASS_NAME + ".main", span.resourceName);
    assertTrue(span.isFinished());
    assertArrayEquals(new String[] {"tag1:foo1", "tag2:foo2", SPAN_PROBEID_TAG}, span.tags);
  }

  @Test
  public void lineRangeSimpleSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer = installSingleSpan(CLASS_NAME + ".java", 4, 8);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(1, tracer.spans.size());
    MockSpan span = tracer.spans.get(0);
    assertEquals(CLASS_NAME + ".main:L4-8", span.resourceName);
    assertTrue(span.isFinished());
    assertArrayEquals(new String[] {SPAN_PROBEID_TAG}, span.tags);
    probeStatusSink.addEmitting(eq(SPAN_ID));
  }

  @Test
  public void lineRangeErrorSimpleSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer = installSingleSpan(CLASS_NAME + ".java", 5, 9);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    // instrumentation cannot happen, so no span generated
    assertEquals(0, tracer.spans.size());
  }

  @Test
  public void invalidLineSimpleSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer = installSingleSpan(CLASS_NAME + ".java", 4, 10);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, tracer.spans.size());
    verify(probeStatusSink).addError(eq(SPAN_ID), eq("No line info for range 4-10"));
  }

  @Test
  public void spanThrows() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer = installSingleSpan(CLASS_NAME, "main", "int (java.lang.String)");
    tracer.setThrowing(true);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, tracer.spans.size());
  }

  private MockTracer installSingleSpan(
      String typeName, String methodName, String signature, String... tags) {
    SpanProbe spanProbe = createSpan(SPAN_ID, typeName, methodName, signature, tags);
    return installSpanProbes(spanProbe);
  }

  private MockTracer installSingleSpan(
      String sourceFile, int lineFrom, int lineTill, String... tags) {
    SpanProbe spanProbe = createSpan(SPAN_ID, sourceFile, lineFrom, lineTill, tags);
    return installSpanProbes(spanProbe);
  }

  private MockTracer installSpanProbes(SpanProbe... spanProbes) {
    return installSpanProbes(
        Configuration.builder().setService(SERVICE_NAME).add(spanProbes).build());
  }

  private MockTracer installSpanProbes(Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDynamicInstrumentationEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationClassFileDumpEnabled()).thenReturn(true);
    when(config.isDynamicInstrumentationVerifyByteCode()).thenReturn(true);
    when(config.getFinalDebuggerSnapshotUrl())
        .thenReturn("http://localhost:8126/debugger/v1/input");
    when(config.getFinalDebuggerSymDBUrl()).thenReturn("http://localhost:8126/symdb/v1/input");
    probeStatusSink = mock(ProbeStatusSink.class);
    currentTransformer =
        new DebuggerTransformer(
            config,
            configuration,
            null,
            new ProbeMetadata(),
            new DebuggerSink(config, probeStatusSink));
    instr.addTransformer(currentTransformer);
    mockSink = new MockSink(config, probeStatusSink);
    DebuggerAgentHelper.injectSink(mockSink);
    DebuggerContext.initProbeResolver(null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    MockTracer mockTracer = new MockTracer();
    DebuggerContext.initTracer(mockTracer);
    return mockTracer;
  }

  static class MockTracer implements DebuggerContext.Tracer {
    List<MockSpan> spans = new ArrayList<>();
    boolean throwing;

    @Override
    public DebuggerSpan createSpan(String encodedProbeId, String resourceName, String[] tags) {
      if (throwing) {
        throw new IllegalArgumentException("oops");
      }
      MockSpan mockSpan = new MockSpan(resourceName, tags);
      spans.add(mockSpan);
      return mockSpan;
    }

    public void setThrowing(boolean value) {
      this.throwing = value;
    }
  }

  static class MockSpan implements DebuggerSpan {
    boolean finished;
    Throwable throwable;
    String resourceName;
    String[] tags;

    public MockSpan(String resourceName, String[] tags) {
      this.resourceName = resourceName;
      this.tags = tags;
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public void setError(Throwable t) {
      this.throwable = t;
    }

    public boolean isFinished() {
      return finished;
    }
  }

  private static SpanProbe createSpan(
      ProbeId id, String typeName, String methodName, String signature, String[] tags) {
    return SpanProbe.builder()
        .probeId(id)
        .where(typeName, methodName, signature)
        .tags(tags)
        .build();
  }

  private static SpanProbe createSpan(
      ProbeId id, String sourceFile, int lineFrom, int lineTill, String[] tags) {
    return SpanProbe.builder().probeId(id).where(sourceFile, lineFrom, lineTill).tags(tags).build();
  }
}
