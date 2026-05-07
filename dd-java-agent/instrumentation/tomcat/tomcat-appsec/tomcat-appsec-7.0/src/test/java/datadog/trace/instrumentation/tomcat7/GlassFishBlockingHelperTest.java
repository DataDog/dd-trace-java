package datadog.trace.instrumentation.tomcat7;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.internal.TraceSegment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class GlassFishBlockingHelperTest {

  // ------- commitBlocking() -------

  @Test
  void commitBlocking_nullResponse_returnsFalse() {
    assertFalse(GlassFishBlockingHelper.commitBlocking(null, null, rba(403)));
  }

  @Test
  void commitBlocking_committedResponse_returnsFalse() {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(true);
    assertFalse(GlassFishBlockingHelper.commitBlocking(null, resp, rba(403)));
  }

  @Test
  void commitBlocking_blockingContentTypeNone_setsStatusWithoutBody() throws IOException {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(false);

    assertTrue(
        GlassFishBlockingHelper.commitBlocking(
            null, resp, new Flow.Action.RequestBlockingAction(403, BlockingContentType.NONE)));

    verify(resp).setStatus(403);
    verify(resp).flushBuffer();
    verify(resp, never()).setHeader(eq("Content-Type"), any());
    verify(resp, never()).getOutputStream();
  }

  @Test
  void commitBlocking_withJsonAccept_writesJsonBody() throws IOException {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("application/json");
    TestServletOutputStream out = new TestServletOutputStream();
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(false);
    when(resp.getOutputStream()).thenReturn(out);

    assertTrue(GlassFishBlockingHelper.commitBlocking(req, resp, rba(403)));

    verify(resp).setStatus(403);
    verify(resp).setHeader(eq("Content-Type"), contains("json"));
    verify(resp).setHeader(eq("Content-Length"), any());
    assertTrue(out.getBytes().length > 0);
    verify(resp).flushBuffer();
  }

  @Test
  void commitBlocking_withHtmlAccept_writesHtmlBody() throws IOException {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("Accept")).thenReturn("text/html");
    TestServletOutputStream out = new TestServletOutputStream();
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(false);
    when(resp.getOutputStream()).thenReturn(out);

    assertTrue(GlassFishBlockingHelper.commitBlocking(req, resp, rba(403)));

    verify(resp).setHeader(eq("Content-Type"), contains("html"));
    assertTrue(out.getBytes().length > 0);
  }

  @Test
  void commitBlocking_nullRequest_defaultsToJsonBody() throws IOException {
    TestServletOutputStream out = new TestServletOutputStream();
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(false);
    when(resp.getOutputStream()).thenReturn(out);

    assertTrue(GlassFishBlockingHelper.commitBlocking(null, resp, rba(403)));

    verify(resp).setStatus(403);
    assertTrue(out.getBytes().length > 0);
  }

  @Test
  void commitBlocking_ioException_returnsFalse() throws IOException {
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(false);
    when(resp.getOutputStream()).thenThrow(new IOException("stream error"));

    assertFalse(GlassFishBlockingHelper.commitBlocking(null, resp, rba(403)));
  }

  // ------- tryBlock() -------

  @Test
  void tryBlock_withBrf_commitsViaFunctionAndReturnsTrue() throws Exception {
    TraceSegment segment = mock(TraceSegment.class);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    RequestContext reqCtx = mockReqCtx(brf, segment);

    Flow.Action.RequestBlockingAction action = rba(403);
    assertTrue(GlassFishBlockingHelper.tryBlock(reqCtx, null, null, action));

    verify(brf).tryCommitBlockingResponse(segment, action);
    verify(segment).effectivelyBlocked();
  }

  @Test
  void tryBlock_noBrf_fallbackSucceeds_returnsTrue() throws IOException {
    TraceSegment segment = mock(TraceSegment.class);
    RequestContext reqCtx = mockReqCtx(null, segment);
    TestServletOutputStream out = new TestServletOutputStream();
    HttpServletResponse resp = mock(HttpServletResponse.class);
    when(resp.isCommitted()).thenReturn(false);
    when(resp.getOutputStream()).thenReturn(out);

    assertTrue(GlassFishBlockingHelper.tryBlock(reqCtx, null, resp, rba(403)));
    verify(segment).effectivelyBlocked();
  }

  @Test
  void tryBlock_noBrf_nullFallbackResponse_returnsFalse() {
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getBlockResponseFunction()).thenReturn(null);

    assertFalse(GlassFishBlockingHelper.tryBlock(reqCtx, null, null, rba(403)));
    verify(reqCtx, never()).getTraceSegment();
  }

  @Test
  void tryBlock_brfThrows_returnsFalse() throws Exception {
    TraceSegment segment = mock(TraceSegment.class);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    RequestContext reqCtx = mockReqCtx(brf, segment);
    doThrow(new RuntimeException("commit failed"))
        .when(brf)
        .tryCommitBlockingResponse(any(), any(Flow.Action.RequestBlockingAction.class));

    assertFalse(GlassFishBlockingHelper.tryBlock(reqCtx, null, null, rba(403)));
    verify(segment, never()).effectivelyBlocked();
  }

  @Test
  void tryBlock_effectivelyBlockedThrows_stillReturnsTrue() throws Exception {
    TraceSegment segment = mock(TraceSegment.class);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    RequestContext reqCtx = mockReqCtx(brf, segment);
    doThrow(new RuntimeException("span already finished")).when(segment).effectivelyBlocked();

    assertTrue(GlassFishBlockingHelper.tryBlock(reqCtx, null, null, rba(403)));
  }

  // ------- Helpers -------

  private static Flow.Action.RequestBlockingAction rba(int statusCode) {
    return new Flow.Action.RequestBlockingAction(statusCode, BlockingContentType.AUTO);
  }

  private static RequestContext mockReqCtx(BlockResponseFunction brf, TraceSegment segment) {
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getBlockResponseFunction()).thenReturn(brf);
    when(reqCtx.getTraceSegment()).thenReturn(segment);
    return reqCtx;
  }

  private static final class TestServletOutputStream extends ServletOutputStream {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener listener) {}

    @Override
    public void write(int b) throws IOException {
      buffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      buffer.write(b, off, len);
    }

    public byte[] getBytes() {
      return buffer.toByteArray();
    }
  }
}
