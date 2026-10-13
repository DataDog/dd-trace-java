package datadog.trace.api.gateway;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link BlockResponseFunction} default methods: {@code
 * tryCommitBlockingResponse(TraceSegment, RequestBlockingAction)}, which forwards every field of
 * the action to the abstract parameter-based method, and {@code
 * tryCommitBlockingResponse(RequestContext, RequestBlockingAction)}, which reports a block failure
 * to {@link AppSecContext#reportBlockFailure()} when the blocking response cannot be committed.
 */
class BlockResponseFunctionTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  @Test
  void segmentOverloadForwardsAllActionFieldsToParameterBasedMethod() {
    Map<String, String> extraHeaders = new HashMap<>();
    extraHeaders.put("X-Custom", "custom-value");
    extraHeaders.put("Location", "https://example.com/blocked");
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(
            418, BlockingContentType.HTML, extraHeaders, "security-response-id");
    TraceSegment segment = TraceSegment.NoOp.INSTANCE;
    TestBlockResponseFunction brf = new TestBlockResponseFunction(true);

    assertTrue(brf.tryCommitBlockingResponse(segment, rba));

    assertEquals(1, brf.invocations);
    assertSame(segment, brf.lastSegment);
    assertEquals(418, brf.lastStatusCode);
    assertEquals(BlockingContentType.HTML, brf.lastTemplateType);
    assertSame(extraHeaders, brf.lastExtraHeaders);
    assertEquals("security-response-id", brf.lastSecurityResponseId);
  }

  @Test
  void segmentOverloadForwardsRedirectActionFields() {
    Flow.Action.RequestBlockingAction rba =
        Flow.Action.RequestBlockingAction.forRedirect(
            302, "https://example.com/redirect", "redirect-response-id");
    TestBlockResponseFunction brf = new TestBlockResponseFunction(true);

    assertTrue(brf.tryCommitBlockingResponse(TraceSegment.NoOp.INSTANCE, rba));

    assertEquals(302, brf.lastStatusCode);
    assertEquals(BlockingContentType.NONE, brf.lastTemplateType);
    assertEquals(singletonMap("Location", "https://example.com/redirect"), brf.lastExtraHeaders);
    assertEquals("redirect-response-id", brf.lastSecurityResponseId);
  }

  @Test
  void segmentOverloadForwardsDefaultsAndPropagatesFailedCommit() {
    TestBlockResponseFunction brf = new TestBlockResponseFunction(false);

    assertFalse(brf.tryCommitBlockingResponse(TraceSegment.NoOp.INSTANCE, RBA));

    assertEquals(1, brf.invocations);
    assertEquals(403, brf.lastStatusCode);
    assertEquals(BlockingContentType.AUTO, brf.lastTemplateType);
    assertEquals(emptyMap(), brf.lastExtraHeaders);
    assertNull(brf.lastSecurityResponseId);
  }

  @Test
  void doesNotReportBlockFailureWhenCommitSucceeds() {
    CountingAppSecContext appSecCtx = new CountingAppSecContext();
    TestRequestContext ctx = new TestRequestContext(appSecCtx);
    TestBlockResponseFunction brf = new TestBlockResponseFunction(true);

    assertTrue(brf.tryCommitBlockingResponse(ctx, RBA));

    assertSame(ctx.traceSegment, brf.lastSegment);
    assertEquals(403, brf.lastStatusCode);
    assertEquals(BlockingContentType.AUTO, brf.lastTemplateType);
    assertEquals(0, appSecCtx.blockFailures);
  }

  @Test
  void reportsBlockFailureWhenCommitFails() {
    CountingAppSecContext appSecCtx = new CountingAppSecContext();
    TestRequestContext ctx = new TestRequestContext(appSecCtx);
    TestBlockResponseFunction brf = new TestBlockResponseFunction(false);

    assertFalse(brf.tryCommitBlockingResponse(ctx, RBA));

    assertSame(ctx.traceSegment, brf.lastSegment);
    assertEquals(1, appSecCtx.blockFailures);
  }

  @Test
  void doesNotThrowWhenAppSecSlotDoesNotHoldAnAppSecContext() {
    TestBlockResponseFunction brf = new TestBlockResponseFunction(false);

    assertFalse(brf.tryCommitBlockingResponse(new TestRequestContext(null), RBA));
    assertFalse(brf.tryCommitBlockingResponse(new TestRequestContext("not an AppSecContext"), RBA));
  }

  private static final class CountingAppSecContext implements AppSecContext {
    private int blockFailures;

    @Override
    public boolean isManuallyKept() {
      return false;
    }

    @Override
    public void reportBlockFailure() {
      blockFailures++;
    }
  }

  private static final class TestBlockResponseFunction implements BlockResponseFunction {
    private final boolean committed;
    private TraceSegment lastSegment;
    private int lastStatusCode;
    private BlockingContentType lastTemplateType;
    private Map<String, String> lastExtraHeaders;
    private String lastSecurityResponseId;
    private int invocations;

    private TestBlockResponseFunction(boolean committed) {
      this.committed = committed;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      this.lastSegment = segment;
      this.lastStatusCode = statusCode;
      this.lastTemplateType = templateType;
      this.lastExtraHeaders = extraHeaders;
      this.lastSecurityResponseId = securityResponseId;
      this.invocations++;
      return committed;
    }
  }

  private static final class TestRequestContext implements RequestContext {
    private final Object appSecData;
    private final TraceSegment traceSegment = TraceSegment.NoOp.INSTANCE;

    private TestRequestContext(Object appSecData) {
      this.appSecData = appSecData;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getData(RequestContextSlot slot) {
      return slot == RequestContextSlot.APPSEC ? (T) appSecData : null;
    }

    @Override
    public TraceSegment getTraceSegment() {
      return traceSegment;
    }

    @Override
    public void setBlockResponseFunction(BlockResponseFunction blockResponseFunction) {}

    @Override
    public BlockResponseFunction getBlockResponseFunction() {
      return null;
    }

    @Override
    public <T> T getOrCreateMetaStructTop(String key, Function<String, T> defaultValue) {
      return null;
    }

    @Override
    public void setClientIpAddressData(ClientIpAddressData clientIpAddressData) {}

    @Override
    public ClientIpAddressData getClientIpAddressData() {
      return null;
    }

    @Override
    public void close() {}
  }
}
