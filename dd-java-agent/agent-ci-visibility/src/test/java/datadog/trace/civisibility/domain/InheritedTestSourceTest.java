package datadog.trace.civisibility.domain;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.coverage.CoveragePerTestBridge;
import datadog.trace.api.civisibility.coverage.NoOpCoverageStore;
import datadog.trace.api.civisibility.telemetry.NoOpMetricCollector;
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.civisibility.codeowners.NoCodeowners;
import datadog.trace.civisibility.config.ConfigurationErrors;
import datadog.trace.civisibility.config.ExecutionSettings;
import datadog.trace.civisibility.decorator.TestDecorator;
import datadog.trace.civisibility.diff.LineDiff;
import datadog.trace.civisibility.source.LinesResolver;
import datadog.trace.civisibility.source.SourcePathResolver;
import datadog.trace.civisibility.test.ExecutionResults;
import datadog.trace.civisibility.test.ExecutionStrategy;
import java.lang.reflect.Method;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InheritedTestSourceTest {
  private static final String PARENT_SOURCE = "src/test/java/ParentSpec.java";
  private static final String LEAF_SOURCE = "src/test/java/LeafSpec.java";

  static class ParentSpec {
    public void feature() {}
  }

  static class LeafSpec extends ParentSpec {}

  @Test
  void reportsParentSourceAndLinesWhilePreservingConcreteSuiteIdentity() throws Exception {
    Method method = ParentSpec.class.getDeclaredMethod("feature");
    SourcePathResolver sourcePaths = sourcePaths();
    LinesResolver lines = mock(LinesResolver.class);
    when(lines.getMethodLines(method)).thenReturn(new LinesResolver.Lines(10, 15));
    AgentSpan span = mock(AgentSpan.class);
    AgentTracer.SpanBuilder builder = mock(AgentTracer.SpanBuilder.class, RETURNS_SELF);
    when(builder.start()).thenReturn(span);
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.buildSpan(anyString(), any())).thenReturn(builder);
    AgentSpanContext context = mock(AgentSpanContext.class);
    when(context.getTraceId()).thenReturn(DDTraceId.ONE);
    Config config = mock(Config.class);
    when(config.isCiVisibilitySourceDataEnabled()).thenReturn(true);
    TestDecorator decorator = mock(TestDecorator.class);
    when(decorator.component()).thenReturn("spock");
    AgentTracer.TracerAPI original = AgentTracer.get();
    AgentTracer.forceRegister(tracer);
    try {
      new TestImpl(
          context,
          1L,
          "module",
          LeafSpec.class.getName(),
          "feature",
          null,
          null,
          false,
          null,
          LeafSpec.class,
          method,
          InstrumentationType.BUILD,
          TestFrameworkInstrumentation.SPOCK,
          config,
          NoOpMetricCollector.INSTANCE,
          decorator,
          sourcePaths,
          lines,
          NoCodeowners.INSTANCE,
          new NoOpCoverageStore.Factory(),
          new ExecutionResults(),
          ConfigurationErrors.NONE,
          emptyList(),
          ignored -> {});

      verify(span).setTag(Tags.TEST_SUITE, LeafSpec.class.getName());
      verify(span).setTag(Tags.TEST_SOURCE_FILE, PARENT_SOURCE);
      verify(span).setTag(Tags.TEST_SOURCE_START, 10);
      verify(span).setTag(Tags.TEST_SOURCE_END, 15);
    } finally {
      CoveragePerTestBridge.removeThreadLocalCoverageProbes();
      AgentTracer.forceRegister(original);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void checksModifiedLinesInTheDeclaringSource(boolean parentModified) throws Exception {
    Method method = ParentSpec.class.getDeclaredMethod("feature");
    BitSet changedLines = new BitSet();
    changedLines.set(12);
    ExecutionSettings settings = mock(ExecutionSettings.class);
    when(settings.getPullRequestDiff())
        .thenReturn(
            new LineDiff(singletonMap(parentModified ? PARENT_SOURCE : LEAF_SOURCE, changedLines)));
    LinesResolver lines = mock(LinesResolver.class);
    when(lines.getMethodLines(method)).thenReturn(new LinesResolver.Lines(10, 15));
    ExecutionStrategy strategy =
        new ExecutionStrategy(mock(Config.class), settings, sourcePaths(), lines);

    assertEquals(
        parentModified, strategy.isModified(new TestSourceData(LeafSpec.class, method, "feature")));
  }

  private static SourcePathResolver sourcePaths() {
    SourcePathResolver resolver = mock(SourcePathResolver.class);
    when(resolver.getSourcePaths(ParentSpec.class)).thenReturn(singletonList(PARENT_SOURCE));
    when(resolver.getSourcePaths(LeafSpec.class)).thenReturn(singletonList(LEAF_SOURCE));
    return resolver;
  }
}
