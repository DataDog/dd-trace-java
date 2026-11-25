package datadog.trace.civisibility.domain;

import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore;
import datadog.trace.api.civisibility.coverage.NoOpProbes;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.NoCodeowners;
import datadog.trace.civisibility.config.ConfigurationErrors;
import datadog.trace.civisibility.coverage.file.FileCoverageStore;
import datadog.trace.civisibility.coverage.line.LineCoverageStore;
import datadog.trace.civisibility.coverage.probe.ProbeCoverageStore;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionResults;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CoverageCollectorTagTest {

  static Stream<Arguments> stores() {
    SourcePathResolver sourcePaths = mock(SourcePathResolver.class);
    return Stream.of(
        Arguments.of(
            new ProbeCoverageStore.Factory(NoOpMetricCollector.INSTANCE, sourcePaths), "probes"),
        Arguments.of(
            new LineCoverageStore.Factory(NoOpMetricCollector.INSTANCE, sourcePaths), "jacoco"),
        Arguments.of(
            new FileCoverageStore.Factory(NoOpMetricCollector.INSTANCE, sourcePaths), "file"),
        Arguments.of(new NoOpCoverageStore.Factory(), null));
  }

  @ParameterizedTest
  @MethodSource("stores")
  void storeNamesItsCollector(CoverageStore.Factory factory, String collectorName) {
    CoverageStore store = factory.create(null);
    try {
      assertEquals(collectorName, store.getCollectorName());
    } finally {
      store.discard();
    }
  }

  @Test
  void testNamesTheCollectorOfItsCoverage() throws Exception {
    CoverageStore store = mock(CoverageStore.class);
    when(store.getProbes()).thenReturn(NoOpProbes.INSTANCE);
    when(store.getCollectorName()).thenReturn("probes");
    CoverageStore.Factory factory = mock(CoverageStore.Factory.class);
    when(factory.create(any())).thenReturn(store);

    AgentSpan span = startTest(factory);

    verify(span).setTag(Tags.TEST_CODE_COVERAGE_COLLECTOR, "probes");
  }

  @Test
  void testWithoutCoverageNamesNoCollector() throws Exception {
    AgentSpan span = startTest(new NoOpCoverageStore.Factory());

    verify(span, never()).setTag(eq(Tags.TEST_CODE_COVERAGE_COLLECTOR), anyString());
  }

  private static AgentSpan startTest(CoverageStore.Factory coverageStoreFactory) throws Exception {
    AgentSpan span = mock(AgentSpan.class);
    AgentTracer.SpanBuilder builder = mock(AgentTracer.SpanBuilder.class, RETURNS_SELF);
    when(builder.start()).thenReturn(span);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.buildSpan(anyString(), any())).thenReturn(builder);
    AgentSpanContext context = mock(AgentSpanContext.class);
    when(context.getTraceId()).thenReturn(DDTraceId.ONE);
    TestDecorator decorator = mock(TestDecorator.class);
    when(decorator.component()).thenReturn("junit");
    AgentTracer.TracerAPI original = AgentTracer.get();
    AgentTracer.forceRegister(tracer);
    try {
      new TestImpl(
          context,
          1L,
          "module",
          "Suite",
          "test",
          null,
          null,
          false,
          null,
          null,
          null,
          InstrumentationType.BUILD,
          TestFrameworkInstrumentation.JUNIT5,
          mock(Config.class),
          NoOpMetricCollector.INSTANCE,
          decorator,
          mock(SourcePathResolver.class),
          mock(LinesResolver.class),
          NoCodeowners.INSTANCE,
          coverageStoreFactory,
          new ExecutionResults(),
          ConfigurationErrors.NONE,
          emptyList(),
          ignored -> {});
      return span;
    } finally {
      CoveragePerTestBridge.removeThreadLocalCoverageProbes();
      AgentTracer.forceRegister(original);
    }
  }
}
