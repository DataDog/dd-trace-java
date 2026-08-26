package com.datadog.appsec.api.security;

import static com.datadog.appsec.event.data.KnownAddresses.WAF_CONTEXT_PROCESSOR;
import static datadog.trace.api.gateway.RequestContextSlot.APPSEC;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.event.EventProducerService;
import com.datadog.appsec.event.ExpiredSubscriberInfoException;
import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppSecSpanPostProcessorTest {

  private static final String SCHEMA_DERIVATIVE_KEY = "_dd.appsec.s.req.body";
  private static final String FRAMEWORK = "netty";

  @Mock private ApiSecuritySamplerImpl sampler;
  @Mock private EventProducerService producer;
  @Mock private EventProducerService.DataSubscriberInfo subInfo;
  @Mock private AgentSpan span;
  @Mock private RequestContext reqCtx;
  @Mock private TraceSegment traceSegment;
  @Mock private AppSecRequestContext ctx;

  private AppSecSpanPostProcessor processor;

  /**
   * {@link WafMetricCollector} is a mutable static singleton; swap it for a mock so the emitted API
   * Security metrics can be verified, and restore the original instance afterwards.
   */
  private WafMetricCollector wafMetricCollector;

  private WafMetricCollector originalWafMetricCollector;

  @BeforeEach
  void setUp() {
    originalWafMetricCollector = WafMetricCollector.get();
    wafMetricCollector = mock(WafMetricCollector.class);
    WafMetricCollector.INSTANCE = wafMetricCollector;
    processor = new AppSecSpanPostProcessor(sampler, producer);
  }

  @AfterEach
  void tearDown() {
    WafMetricCollector.INSTANCE = originalWafMetricCollector;
  }

  @Test
  void schemaExtractedOnHappyPath() throws ExpiredSubscriberInfoException {
    // given
    stubUpToPublish();
    when(span.getTag(COMPONENT)).thenReturn(FRAMEWORK);
    when(ctx.getDerivativeKeys()).thenReturn(singleton(SCHEMA_DERIVATIVE_KEY));

    // when
    processor.process(span, () -> false);

    // then
    verifyUpToPublish();
    verify(span).getTag(COMPONENT);
    verify(ctx).getDerivativeKeys();
    verify(wafMetricCollector).apiSecurityRequestSchema(FRAMEWORK);
    verify(ctx).commitDerivatives(traceSegment);
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void noSchemaMetricReportedWhenDerivativesAreEmpty() throws ExpiredSubscriberInfoException {
    // given
    stubUpToPublish();
    when(span.getTag(COMPONENT)).thenReturn(FRAMEWORK);
    when(ctx.getDerivativeKeys()).thenReturn(emptySet());

    // when
    processor.process(span, () -> false);

    // then
    verifyUpToPublish();
    verify(span).getTag(COMPONENT);
    verify(ctx).getDerivativeKeys();
    verify(wafMetricCollector).apiSecurityRequestNoSchema(FRAMEWORK);
    verify(ctx).commitDerivatives(traceSegment);
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void noSchemaMetricReportedWhenNoDerivativeIsASchema() throws ExpiredSubscriberInfoException {
    // given
    stubUpToPublish();
    when(span.getTag(COMPONENT)).thenReturn(FRAMEWORK);
    when(ctx.getDerivativeKeys()).thenReturn(singleton("_dd.appsec.fp.http.header"));

    // when
    processor.process(span, () -> false);

    // then
    verifyUpToPublish();
    verify(span).getTag(COMPONENT);
    verify(ctx).getDerivativeKeys();
    verify(wafMetricCollector).apiSecurityRequestNoSchema(FRAMEWORK);
    verify(ctx).commitDerivatives(traceSegment);
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void nullFrameworkReportedWhenComponentTagIsMissing() throws ExpiredSubscriberInfoException {
    // given
    stubUpToPublish();
    when(span.getTag(COMPONENT)).thenReturn(null);
    when(ctx.getDerivativeKeys()).thenReturn(singleton(SCHEMA_DERIVATIVE_KEY));

    // when
    processor.process(span, () -> false);

    // then
    verifyUpToPublish();
    verify(span).getTag(COMPONENT);
    verify(ctx).getDerivativeKeys();
    verify(wafMetricCollector).apiSecurityRequestSchema(null);
    verify(ctx).commitDerivatives(traceSegment);
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void noSchemaExtractedIfSamplingIsFalse() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getData(APPSEC)).thenReturn(ctx);
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(true);
    when(sampler.sampleRequest(ctx)).thenReturn(false);

    // when
    processor.process(span, () -> false);

    // then
    verify(span).getRequestContext();
    verify(reqCtx).getData(APPSEC);
    verify(ctx).isKeepOpenForApiSecurityPostProcessing();
    verify(sampler).sampleRequest(ctx);
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void permitIsReleasedEvenIfRequestContextCloseThrows() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getData(APPSEC)).thenReturn(ctx);
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(true);
    when(sampler.sampleRequest(ctx)).thenReturn(true);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(WAF_CONTEXT_PROCESSOR)).thenReturn(null);
    doThrow(new RuntimeException()).when(ctx).close();

    // when
    processor.process(span, () -> false);

    // then
    verify(span).getRequestContext();
    verify(reqCtx).getData(APPSEC);
    verify(ctx).isKeepOpenForApiSecurityPostProcessing();
    verify(sampler).sampleRequest(ctx);
    verify(span).getTag(COMPONENT);
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(WAF_CONTEXT_PROCESSOR);
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void contextIsCleanedUpOnTimeout() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getData(APPSEC)).thenReturn(ctx);
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(true);

    // when
    processor.process(span, () -> true);

    // then
    verify(span).getRequestContext();
    verify(reqCtx).getData(APPSEC);
    verify(ctx).isKeepOpenForApiSecurityPostProcessing();
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void processNullRequestContextDoesNothing() {
    // given
    when(span.getRequestContext()).thenReturn(null);

    // when
    processor.process(span, () -> false);

    // then
    verify(span).getRequestContext();
    verifyNoOtherInteractions();
  }

  @Test
  void processNullAppSecRequestContextDoesNothing() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getData(APPSEC)).thenReturn(null);

    // when
    processor.process(span, () -> false);

    // then
    verify(span).getRequestContext();
    verify(reqCtx).getData(APPSEC);
    verifyNoOtherInteractions();
  }

  @Test
  void processAlreadyClosedContextDoesNothing() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getData(APPSEC)).thenReturn(ctx);
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(false);

    // when
    processor.process(span, () -> false);

    // then
    verify(span).getRequestContext();
    verify(reqCtx).getData(APPSEC);
    verify(ctx).isKeepOpenForApiSecurityPostProcessing();
    verifyNoOtherInteractions();
  }

  @Test
  void processThrowsOnNullSpan() {
    // when / then
    assertThrows(NullPointerException.class, () -> processor.process(null, () -> false));
    verifyNoOtherInteractions();
  }

  @Test
  void emptyEventSubscriptionDoesNotBreakTheProcess() {
    // given
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getData(APPSEC)).thenReturn(ctx);
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(true);
    when(sampler.sampleRequest(ctx)).thenReturn(true);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(WAF_CONTEXT_PROCESSOR)).thenReturn(subInfo);
    when(subInfo.isEmpty()).thenReturn(true);

    // when
    processor.process(span, () -> false);

    // then
    verify(span).getRequestContext();
    verify(reqCtx).getData(APPSEC);
    verify(ctx).isKeepOpenForApiSecurityPostProcessing();
    verify(sampler).sampleRequest(ctx);
    verify(span).getTag(COMPONENT);
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(WAF_CONTEXT_PROCESSOR);
    verify(subInfo).isEmpty();
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  @Test
  void expiredEventSubscriptionDoesNotBreakTheProcess() throws ExpiredSubscriberInfoException {
    // given
    stubUpToPublish();
    when(span.getTag(COMPONENT)).thenReturn(FRAMEWORK);
    doThrow(new ExpiredSubscriberInfoException())
        .when(producer)
        .publishDataEvent(eq(subInfo), eq(ctx), any(), any());

    // when
    processor.process(span, () -> false);

    // then
    verifyUpToPublish();
    verify(span).getTag(COMPONENT);
    verifyCleanup();
    verifyNoOtherInteractions();
  }

  /** Stubs every interaction up to (and including) the successful data event publication. */
  private void stubUpToPublish() {
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getData(APPSEC)).thenReturn(ctx);
    when(ctx.isKeepOpenForApiSecurityPostProcessing()).thenReturn(true);
    when(sampler.sampleRequest(ctx)).thenReturn(true);
    when(reqCtx.getTraceSegment()).thenReturn(traceSegment);
    when(producer.getDataSubscribers(WAF_CONTEXT_PROCESSOR)).thenReturn(subInfo);
    when(subInfo.isEmpty()).thenReturn(false);
  }

  private void verifyUpToPublish() throws ExpiredSubscriberInfoException {
    verify(span).getRequestContext();
    verify(reqCtx).getData(APPSEC);
    verify(ctx).isKeepOpenForApiSecurityPostProcessing();
    verify(sampler).sampleRequest(ctx);
    verify(reqCtx).getTraceSegment();
    verify(producer).getDataSubscribers(WAF_CONTEXT_PROCESSOR);
    verify(subInfo).isEmpty();
    verify(producer).publishDataEvent(eq(subInfo), eq(ctx), any(), any());
  }

  private void verifyCleanup() {
    verify(ctx).setKeepOpenForApiSecurityPostProcessing(false);
    verify(ctx).closeWafContext();
    verify(ctx).close();
    verify(sampler).releaseOne();
  }

  /** Mirrors Spock's {@code 0 * _}: no interaction beyond the explicitly verified ones. */
  private void verifyNoOtherInteractions() {
    verifyNoMoreInteractions(
        sampler, producer, subInfo, span, reqCtx, traceSegment, ctx, wafMetricCollector);
  }
}
