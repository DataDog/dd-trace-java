package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static utils.InstrumentationTestHelper.compileAndLoadClass;

import com.datadog.debugger.probe.SpanProbe;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.DebuggerSpan;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.joor.Reflect;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class SpanProbeInstrumentationTest extends ProbeInstrumentationTest {
  private static final String SPAN_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";

  @Test
  public void methodSimpleSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer =
        installSingleSpan(CLASS_NAME, "main", "int (java.lang.String)", "main-span", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(1, tracer.spans.size());
    assertEquals("main-span", tracer.spans.get(0).name);
    assertTrue(tracer.spans.get(0).isFinished());
  }

  @Test
  public void methodSimpleSpanWithTags() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer =
        installSingleSpan(
            CLASS_NAME, "main", "int (java.lang.String)", "main-span", "tag1:foo1", "tag2:foo2");
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(1, tracer.spans.size());
    MockSpan span = tracer.spans.get(0);
    assertEquals("main-span", span.name);
    assertTrue(span.isFinished());
    Assert.assertArrayEquals(new String[] {"tag1:foo1", "tag2:foo2"}, span.tags);
  }

  @Test
  public void lineRangeSimpleSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer = installSingleSpan(CLASS_NAME + ".java", 4, 8, "main-span", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(1, tracer.spans.size());
    assertEquals("main-span", tracer.spans.get(0).name);
    assertTrue(tracer.spans.get(0).isFinished());
  }

  @Test
  public void invalidLineSimpleSpan() throws IOException, URISyntaxException {
    final String CLASS_NAME = "CapturedSnapshot01";
    MockTracer tracer = installSingleSpan(CLASS_NAME + ".java", 4, 10, "main-span", null);
    Class<?> testClass = compileAndLoadClass(CLASS_NAME);
    int result = Reflect.on(testClass).call("main", "1").get();
    assertEquals(3, result);
    assertEquals(0, tracer.spans.size());
    assertEquals(1, mockSink.getCurrentDiagnostics().size());
    assertEquals(
        "No line info for range 4-10", mockSink.getCurrentDiagnostics().get(0).getMessage());
  }

  private MockTracer installSingleSpan(
      String typeName, String methodName, String signature, String spanName, String... tags) {
    SpanProbe spanProbe = createSpan(SPAN_ID, spanName, typeName, methodName, signature, tags);
    return installSpanProbes(spanProbe);
  }

  private MockTracer installSingleSpan(
      String sourceFile, int lineFrom, int lineTill, String spanName, String... tags) {
    SpanProbe spanProbe = createSpan(SPAN_ID, spanName, sourceFile, lineFrom, lineTill, tags);
    return installSpanProbes(spanProbe);
  }

  private MockTracer installSpanProbes(SpanProbe... spanProbes) {
    return installSpanProbes(
        Configuration.builder()
            .setService(SERVICE_NAME)
            .addSpanProbes(Arrays.asList(spanProbes))
            .build());
  }

  private MockTracer installSpanProbes(Configuration configuration) {
    Config config = mock(Config.class);
    when(config.isDebuggerEnabled()).thenReturn(true);
    when(config.isDebuggerClassFileDumpEnabled()).thenReturn(true);
    currentTransformer = new DebuggerTransformer(config, configuration);
    instr.addTransformer(currentTransformer);
    mockSink = new MockSink();
    DebuggerContext.init(mockSink, null, null);
    DebuggerContext.initClassFilter(new DenyListHelper(null));
    MockTracer mockTracer = new MockTracer();
    DebuggerContext.initTracer(mockTracer);
    return mockTracer;
  }

  static class MockTracer implements DebuggerContext.Tracer {
    List<MockSpan> spans = new ArrayList<>();

    @Override
    public DebuggerSpan createSpan(String operationName, String[] tags) {
      MockSpan mockSpan = new MockSpan(operationName, tags);
      spans.add(mockSpan);
      return mockSpan;
    }
  }

  static class MockSpan implements DebuggerSpan {
    boolean finished;
    String name;
    String[] tags;

    public MockSpan(String name, String[] tags) {
      this.name = name;
      this.tags = tags;
    }

    @Override
    public void finish() {
      finished = true;
    }

    public boolean isFinished() {
      return finished;
    }
  }

  private static SpanProbe createSpan(
      String id,
      String spanName,
      String typeName,
      String methodName,
      String signature,
      String[] tags) {
    return SpanProbe.builder()
        .probeId(id)
        .where(typeName, methodName, signature)
        .tags(tags)
        .name(spanName)
        .build();
  }

  private static SpanProbe createSpan(
      String id, String spanName, String sourceFile, int lineFrom, int lineTill, String[] tags) {
    return SpanProbe.builder()
        .probeId(id)
        .where(sourceFile, lineFrom, lineTill)
        .tags(tags)
        .name(spanName)
        .build();
  }
}
