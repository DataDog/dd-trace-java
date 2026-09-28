package datadog.trace.api.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code tryCommitBlockingResponse(RequestContext, RequestBlockingAction)} default
 * method, which reports a block failure to {@link AppSecContext#reportBlockFailure()} when the
 * blocking response cannot be committed.
 */
class BlockResponseFunctionTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

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
