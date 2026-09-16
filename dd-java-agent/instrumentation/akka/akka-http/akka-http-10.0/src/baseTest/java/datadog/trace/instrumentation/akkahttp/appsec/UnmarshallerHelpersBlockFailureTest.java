package datadog.trace.instrumentation.akkahttp.appsec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.ClientIpAddressData;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code UnmarshallerHelpers.tryBlock() -> AppSecContext.reportBlockFailure()} path.
 * Hand-written test doubles are used because Mockito is only on this module's test runtime
 * classpath, not its test compile classpath (see {@code gradle/java_deps.gradle}: {@code
 * testRuntimeOnly libs.mokito.core}).
 */
class UnmarshallerHelpersBlockFailureTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  @Test
  void reportsBlockFailureWhenBlockingResponseCannotBeCommitted() {
    CountingAppSecContext appSecCtx = new CountingAppSecContext();
    TestRequestContext ctx =
        new TestRequestContext(new TestBlockResponseFunction(false), appSecCtx);

    BlockingException exception = UnmarshallerHelpers.tryBlock(ctx, RBA, "for test");

    assertNull(exception);
    assertEquals(1, appSecCtx.blockFailures);
    assertSame(RBA, ctx.brf.lastAction);
    assertSame(ctx.traceSegment, ctx.brf.lastSegment);
  }

  @Test
  void doesNotReportBlockFailureWhenBlockingResponseIsCommitted() {
    CountingAppSecContext appSecCtx = new CountingAppSecContext();
    TestRequestContext ctx = new TestRequestContext(new TestBlockResponseFunction(true), appSecCtx);

    BlockingException exception = UnmarshallerHelpers.tryBlock(ctx, RBA, "for test");

    assertNotNull(exception);
    assertEquals("Blocked request (for test)", exception.getMessage());
    assertEquals(0, appSecCtx.blockFailures);
  }

  @Test
  void doesNotReportOrThrowWhenNoBlockResponseFunctionIsRegistered() {
    CountingAppSecContext appSecCtx = new CountingAppSecContext();
    TestRequestContext ctx = new TestRequestContext(null, appSecCtx);

    BlockingException exception = UnmarshallerHelpers.tryBlock(ctx, RBA, "for test");

    assertNull(exception);
    assertEquals(0, appSecCtx.blockFailures);
  }

  @Test
  void doesNotThrowWhenAppSecSlotDoesNotHoldAnAppSecContext() {
    TestRequestContext nullSlot =
        new TestRequestContext(new TestBlockResponseFunction(false), null);
    assertNull(UnmarshallerHelpers.tryBlock(nullSlot, RBA, "for test"));

    TestRequestContext foreignSlot =
        new TestRequestContext(new TestBlockResponseFunction(false), "not an AppSecContext");
    assertNull(UnmarshallerHelpers.tryBlock(foreignSlot, RBA, "for test"));
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
    private Flow.Action.RequestBlockingAction lastAction;

    private TestBlockResponseFunction(boolean committed) {
      this.committed = committed;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment, Flow.Action.RequestBlockingAction rba) {
      this.lastAction = rba;
      return BlockResponseFunction.super.tryCommitBlockingResponse(segment, rba);
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      this.lastSegment = segment;
      return committed;
    }
  }

  private static final class TestRequestContext implements RequestContext {
    private final TestBlockResponseFunction brf;
    private final Object appSecData;
    private final TraceSegment traceSegment = TraceSegment.NoOp.INSTANCE;

    private TestRequestContext(TestBlockResponseFunction brf, Object appSecData) {
      this.brf = brf;
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
      return brf;
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
