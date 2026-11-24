package com.datadog.appsec.api.security;

import static com.datadog.appsec.event.data.KnownAddresses.WAF_CONTEXT_PROCESSOR;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.EventProducerService.DataSubscriberInfo;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class AppSecSpanPostProcessorTest extends DDJavaSpecification {

  private static final String SCHEMA_DERIVATIVE_PREFIX = "_dd.appsec.s.";
  private static final String FRAMEWORK = "netty";
  private static final WafMetricCollector ORIGINAL_METRIC_COLLECTOR = WafMetricCollector.get();

  private final WafMetricCollector wafMetricCollector = mock(WafMetricCollector.class);
  private final ApiSecuritySamplerImpl sampler = mock(ApiSecuritySamplerImpl.class);
  private final EventProducerService producer = mock(EventProducerService.class);
  private final DataSubscriberInfo subInfo = mock(DataSubscriberInfo.class);
  private final AgentSpan span = mock(AgentSpan.class);
  private final RequestContext reqCtx = mock(RequestContext.class);
  private final TraceSegment traceSegment = mock(TraceSegment.class);
  private final AppSecRequestContext ctx = mock(AppSecRequestContext.class);
  private final AppSecSpanPostProcessor processor = new AppSecSpanPostProcessor(sampler, producer);

  @BeforeEach
  void setup() {
    WafMetricCollector.INSTANCE = wafMetricCollector;
  }

  @AfterEach
  void cleanup() {
    WafMetricCollector.INSTANCE = ORIGINAL_METRIC_COLLECTOR;
  }

  @Test
  void schemaExtractedOnHappyPath() throws Exception {
    // given
    stubOpenContext();
    when(sampler.sampleRequest(any())).thenReturn(true);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(WAF_CONTEXT_PROCESSOR)).thenReturn(subInfo);
    when(subInfo.isEmpty()).thenReturn(false);
    when(ctx.getApiSecurityFramework()).thenReturn(FRAMEWORK);
    when(ctx.hasDerivativeKeyStartingWith(SCHEMA_DERIVATIVE_PREFIX)).thenReturn(true);

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verifyOpenContextLookup();
    verify(sampler).sampleRequest(any());
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(WAF_CONTEXT_PROCESSOR);
    verify(subInfo).isEmpty();
    verify(ctx).getApiSecurityFramework();
    verify(producer).publishDataEvent(any(), same(ctx), any(), any());
    verify(ctx).hasDerivativeKeyStartingWith(SCHEMA_DERIVATIVE_PREFIX);
    verify(wafMetricCollector).apiSecurityRequestSchema(FRAMEWORK);
    verify(ctx).commitDerivatives(traceSegment);
    verifyContextCleanup();
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void noSchemaExtractedIfSamplingIsFalse() {
    // given
    stubOpenContext();
    when(sampler.sampleRequest(any())).thenReturn(false);

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verifyOpenContextLookup();
    verify(sampler).sampleRequest(any());
    verifyContextCleanup();
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void permitIsReleasedEvenIfRequestContextCloseThrows() {
    // given
    stubOpenContext();
    when(sampler.sampleRequest(any())).thenReturn(true);
    when(ctx.getApiSecurityFramework()).thenReturn(FRAMEWORK);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(any())).thenReturn(null);
    doThrow(new RuntimeException()).when(ctx).close();

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verifyOpenContextLookup();
    verify(sampler).sampleRequest(any());
    verify(ctx).getApiSecurityFramework();
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(any());
    verifyContextCleanup();
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void contextIsCleanedUpOnTimeout() {
    // given
    stubOpenContext();

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> true));

    // then
    verifyOpenContextLookup();
    verifyContextCleanup();
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void processNullRequestContextDoesNothing() {
    // given
    when(span.getRequestContext()).thenReturn(null);

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verify(span).getRequestContext();
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void processNullAppsecRequestContextDoesNothing() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    doReturn(null).when(reqCtx).getData(any());

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verify(span).getRequestContext();
    verify(reqCtx).getData(any());
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void processAlreadyClosedContextDoesNothing() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    doReturn(ctx).when(reqCtx).getData(any());
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(false);

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verifyOpenContextLookup();
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void processThrowsOnNullSpan() {
    // when
    assertThrows(NullPointerException.class, () -> processor.process(null, () -> false));

    // then
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void emptyEventSubscriptionDoesNotBreakTheProcess() {
    // given
    stubOpenContext();
    when(sampler.sampleRequest(any())).thenReturn(true);
    when(ctx.getApiSecurityFramework()).thenReturn(FRAMEWORK);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(any())).thenReturn(subInfo);
    when(subInfo.isEmpty()).thenReturn(true);

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verifyOpenContextLookup();
    verify(sampler).sampleRequest(any());
    verify(ctx).getApiSecurityFramework();
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(any());
    verify(subInfo).isEmpty();
    verifyContextCleanup();
    verifyNoMoreInteractionsOnMocks();
  }

  @Test
  void expiredEventSubscriptionDoesNotBreakTheProcess() throws Exception {
    // given
    stubOpenContext();
    when(sampler.sampleRequest(any())).thenReturn(true);
    when(ctx.getApiSecurityFramework()).thenReturn(FRAMEWORK);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(any())).thenReturn(subInfo);
    when(subInfo.isEmpty()).thenReturn(false);
    doThrow(new ExpiredSubscriberInfoException())
        .when(producer)
        .publishDataEvent(any(), same(ctx), any(), any());

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verifyOpenContextLookup();
    verify(sampler).sampleRequest(any());
    verify(ctx).getApiSecurityFramework();
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(any());
    verify(subInfo).isEmpty();
    verify(producer).publishDataEvent(any(), same(ctx), any(), any());
    verifyContextCleanup();
    verifyNoMoreInteractionsOnMocks();
  }

  @TableTest({
    "scenario              | hasSchemaDerivative | framework | schemaMetrics | noSchemaMetrics",
    "no schema derivative  | false               | netty     | 0             | 1              ",
    "missing component tag | true                |           | 1             | 0              "
  })
  void schemaExtractionMetricIsReported(
      boolean hasSchemaDerivative, String framework, int schemaMetrics, int noSchemaMetrics)
      throws Exception {
    // given
    stubOpenContext();
    when(sampler.sampleRequest(any())).thenReturn(true);
    when(ctx.getApiSecurityFramework()).thenReturn(framework);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(WAF_CONTEXT_PROCESSOR)).thenReturn(subInfo);
    when(subInfo.isEmpty()).thenReturn(false);
    when(ctx.hasDerivativeKeyStartingWith(SCHEMA_DERIVATIVE_PREFIX))
        .thenReturn(hasSchemaDerivative);

    // when
    assertDoesNotThrow(() -> processor.process(span, () -> false));

    // then
    verifyOpenContextLookup();
    verify(sampler).sampleRequest(any());
    verify(ctx).getApiSecurityFramework();
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(WAF_CONTEXT_PROCESSOR);
    verify(subInfo).isEmpty();
    verify(producer).publishDataEvent(any(), same(ctx), any(), any());
    verify(ctx).hasDerivativeKeyStartingWith(SCHEMA_DERIVATIVE_PREFIX);
    verify(wafMetricCollector, times(schemaMetrics)).apiSecurityRequestSchema(eq(framework));
    verify(wafMetricCollector, times(noSchemaMetrics)).apiSecurityRequestNoSchema(eq(framework));
    verify(ctx).commitDerivatives(traceSegment);
    verifyContextCleanup();
    verifyNoMoreInteractionsOnMocks();
  }

  /** Stubs a span whose AppSec request context is kept open for API Security post-processing. */
  private void stubOpenContext() {
    when(span.getRequestContext()).thenReturn(reqCtx);
    doReturn(ctx).when(reqCtx).getData(any());
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(true);
  }

  private void verifyOpenContextLookup() {
    verify(span).getRequestContext();
    verify(reqCtx).getData(any());
    verify(ctx).isKeepOpenForApiSecurityPostProcessing();
  }

  private void verifyContextCleanup() {
    verify(ctx).setKeepOpenForApiSecurityPostProcessing(false);
    verify(ctx).closeWafContext();
    verify(ctx).close();
    verify(sampler).releaseOne();
  }

  private void verifyNoMoreInteractionsOnMocks() {
    verifyNoMoreInteractions(
        wafMetricCollector, sampler, producer, subInfo, span, reqCtx, traceSegment, ctx);
  }
}
