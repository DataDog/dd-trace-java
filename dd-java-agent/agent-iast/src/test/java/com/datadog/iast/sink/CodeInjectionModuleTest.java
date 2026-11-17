package com.datadog.iast.sink;

import static com.datadog.iast.model.VulnerabilityType.CODE_INJECTION;
import static datadog.trace.api.iast.IastContext.Mode.GLOBAL;
import static datadog.trace.api.iast.SourceTypes.REQUEST_PARAMETER_VALUE;
import static datadog.trace.api.iast.VulnerabilityMarks.CODE_INJECTION_MARK;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.datadog.iast.Dependencies;
import com.datadog.iast.IastGlobalContext;
import com.datadog.iast.IastRequestContext;
import com.datadog.iast.Reporter;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.overhead.OverheadController;
import com.datadog.iast.taint.Ranges;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.sink.CodeInjectionModule;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

class CodeInjectionModuleTest {

  private static final AgentTracer.TracerAPI ORIGINAL_TRACER = AgentTracer.get();
  private static final IastContext.Provider ORIGINAL_CONTEXT_PROVIDER = readContextProvider();

  private IastContext.Provider contextProvider;
  private IastRequestContext ctx;
  private AgentSpan span;
  private AgentTracer.TracerAPI tracer;
  private Reporter reporter;
  private OverheadController overheadController;
  private CodeInjectionModule module;

  @BeforeEach
  void setup() {
    contextProvider =
        Config.get().getIastContextMode() == GLOBAL
            ? new IastGlobalContext.Provider()
            : new IastRequestContext.Provider();
    ctx = (IastRequestContext) contextProvider.buildRequestContext();

    TraceSegment traceSegment = mock(TraceSegment.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.IAST)).thenReturn(ctx);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);

    span = mock(AgentSpan.class);
    when(span.getSpanId()).thenReturn(123456L);
    when(span.getRequestContext()).thenReturn(reqCtx);

    tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getTraceSegment()).thenReturn(traceSegment);

    reporter = mock(Reporter.class);

    overheadController = mock(OverheadController.class);
    when(overheadController.acquireRequest()).thenReturn(true);
    when(overheadController.consumeQuota(any(), any(), any())).thenReturn(true);

    Dependencies dependencies =
        new Dependencies(
            Config.get(),
            reporter,
            overheadController,
            StackWalkerFactory.INSTANCE,
            contextProvider);

    AgentTracer.forceRegister(tracer);
    IastContext.Provider.register(contextProvider);

    module = new CodeInjectionModuleImpl(dependencies);
  }

  @AfterEach
  void cleanup() {
    contextProvider.releaseRequestContext(ctx);
    AgentTracer.forceRegister(ORIGINAL_TRACER);
    writeContextProvider(ORIGINAL_CONTEXT_PROVIDER);
  }

  @ParameterizedTest
  @NullAndEmptySource
  void nullOrEmptyScriptIsIgnored(String script) {
    // a String-typed argument selects the onEval(String) overload, no cast needed
    module.onEval(script);

    // mirrors the Groovy original's `0 * _`: nothing is touched on the early-return path
    verifyNoInteractions(reporter, overheadController, tracer);
  }

  @Test
  void codeInjectionDetectionOnString() {
    String script = "2 + 2";

    // report is not called if the script is not tainted
    module.onEval(script);
    verify(reporter, never()).report(any(), any());

    // report is not called if no active span, even when the script is tainted
    taint(script);
    when(tracer.activeSpan()).thenReturn(null);
    module.onEval(script);
    verify(reporter, never()).report(any(), any());

    // report is called when the script is tainted and there is an active span
    when(tracer.activeSpan()).thenReturn(span);
    module.onEval(script);
    verify(reporter).report(eq(span), argThat(vul -> vul.getType() == CODE_INJECTION));
  }

  @Test
  void codeInjectionDetectionOnStringReader() {
    StringReader reader = new StringReader("2 + 2");

    // report is not called if the reader is not tainted
    module.onEval(reader);
    verify(reporter, never()).report(any(), any());

    // report is called when the reader is tainted
    taint(reader);
    module.onEval(reader);
    verify(reporter).report(eq(span), argThat(vul -> vul.getType() == CODE_INJECTION));
  }

  @Test
  void codeInjectionDetectionOnInputStreamReader() throws IOException {
    try (InputStreamReader reader =
        new InputStreamReader(new ByteArrayInputStream("2 + 2".getBytes()))) {
      // report is not called if the reader is not tainted
      module.onEval(reader);
      verify(reporter, never()).report(any(), any());

      // report is called when the reader is tainted
      taint(reader);
      module.onEval(reader);
      verify(reporter).report(eq(span), argThat(vul -> vul.getType() == CODE_INJECTION));
    }
  }

  @Test
  void unsupportedReaderTypeIsIgnoredEvenWhenTainted() {
    // only StringReader and InputStreamReader are inspected (see CodeInjectionModuleImpl.onEval)
    try (CharArrayReader reader = new CharArrayReader("2 + 2".toCharArray())) {
      taint(reader);

      module.onEval(reader);

      // mirrors the Groovy original's `0 * _`: the unsupported-reader path touches no mock
      verifyNoInteractions(reporter, overheadController, tracer);
    }
  }

  @Test
  void allRangesWithMarkOnScriptAreNotReported() {
    String script = "2 + 2";
    Range[] ranges = markedRanges();
    ctx.getTaintedObjects().taint(script, ranges);

    module.onEval(script);

    verify(reporter, never()).report(any(), any());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allRangesWithMarkOnReaderAreNotReportedArguments")
  void allRangesWithMarkOnReaderAreNotReported(String scenario, Reader reader) throws IOException {
    Range[] ranges = markedRanges();
    ctx.getTaintedObjects().taint(reader, ranges);

    module.onEval(reader);

    verify(reporter, never()).report(any(), any());

    reader.close();
  }

  static Stream<Arguments> allRangesWithMarkOnReaderAreNotReportedArguments() {
    return Stream.of(
        arguments("StringReader", new StringReader("2 + 2")),
        arguments(
            "InputStreamReader",
            new InputStreamReader(new ByteArrayInputStream("2 + 2".getBytes()))));
  }

  private Range[] markedRanges() {
    return new Range[] {
      new Range(0, 1, new Source(REQUEST_PARAMETER_VALUE, "name", "value"), CODE_INJECTION_MARK)
    };
  }

  private void taint(Object value) {
    ctx.getTaintedObjects()
        .taint(
            value, Ranges.forObject(new Source(REQUEST_PARAMETER_VALUE, "name", value.toString())));
  }

  // IastContext.Provider.INSTANCE is a private static field; the Groovy base read and restored it
  // directly, which Java cannot, so snapshot and restore it reflectively to preserve test
  // isolation.
  private static IastContext.Provider readContextProvider() {
    try {
      Field field = IastContext.Provider.class.getDeclaredField("INSTANCE");
      field.setAccessible(true);
      return (IastContext.Provider) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void writeContextProvider(IastContext.Provider provider) {
    try {
      Field field = IastContext.Provider.class.getDeclaredField("INSTANCE");
      field.setAccessible(true);
      field.set(null, provider);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
