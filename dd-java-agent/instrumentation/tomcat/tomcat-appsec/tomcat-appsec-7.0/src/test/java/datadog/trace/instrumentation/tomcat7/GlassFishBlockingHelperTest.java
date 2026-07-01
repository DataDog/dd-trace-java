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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
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

  // ------- processPartsAndBlock() -------

  @Test
  void processPartsAndBlock_formField_skipped() throws Exception {
    Part formField = mockPart(null, "text/plain", new byte[0]);
    RequestContext reqCtx = mockReqCtx(null, mock(TraceSegment.class));
    BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb = mockPassThroughCb();

    assertFalse(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList(formField), reqCtx, null, filenamesCb, null));
    verify(formField).getSubmittedFileName();
    verify(formField, never()).getInputStream();
  }

  @Test
  void processPartsAndBlock_emptyFilename_notAddedToFilenames_butContentRead() throws Exception {
    byte[] content = "data".getBytes();
    Part filePart = mockPart("", "application/octet-stream", content);
    RequestContext reqCtx = mockReqCtx(null, mock(TraceSegment.class));
    BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb = mockPassThroughCb();
    BiFunction<RequestContext, List<String>, Flow<Void>> contentCb = mockPassThroughCb();

    assertFalse(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList(filePart), reqCtx, null, filenamesCb, contentCb));

    verify(filePart).getInputStream();
    verify(filenamesCb, never()).apply(any(), any());
    verify(contentCb).apply(eq(reqCtx), any());
  }

  @Test
  void processPartsAndBlock_normalFilename_reportedViaFilenamesCb() throws Exception {
    Part filePart = mockPart("file.txt", "text/plain", "hello".getBytes());
    RequestContext reqCtx = mockReqCtx(null, mock(TraceSegment.class));
    BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb = mockPassThroughCb();

    assertFalse(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList(filePart), reqCtx, null, filenamesCb, null));

    verify(filenamesCb).apply(eq(reqCtx), eq(Collections.singletonList("file.txt")));
  }

  @Test
  void processPartsAndBlock_contentRead_reportedViaContentCb() throws Exception {
    Part filePart = mockPart("file.bin", "application/octet-stream", new byte[] {1, 2, 3});
    RequestContext reqCtx = mockReqCtx(null, mock(TraceSegment.class));
    BiFunction<RequestContext, List<String>, Flow<Void>> contentCb = mockPassThroughCb();

    assertFalse(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList(filePart), reqCtx, null, null, contentCb));

    verify(contentCb).apply(eq(reqCtx), any());
  }

  @Test
  void processPartsAndBlock_maxFilesLimit_enforced() throws Exception {
    int limit = GlassFishBlockingHelper.MAX_FILE_CONTENT_COUNT;
    Part[] tooMany = new Part[limit + 1];
    for (int i = 0; i <= limit; i++) {
      tooMany[i] = mockPart("f" + i + ".bin", "application/octet-stream", new byte[0]);
    }
    RequestContext reqCtx = mockReqCtx(null, mock(TraceSegment.class));
    BiFunction<RequestContext, List<String>, Flow<Void>> contentCb = mockPassThroughCb();

    assertFalse(
        GlassFishBlockingHelper.processPartsAndBlock(
            Arrays.asList(tooMany), reqCtx, null, null, contentCb));

    verify(contentCb).apply(eq(reqCtx), any(List.class));
    verify(tooMany[limit], never()).getInputStream();
  }

  @Test
  @SuppressWarnings("unchecked")
  void processPartsAndBlock_getInputStreamThrows_emptyStringFallback() throws Exception {
    Part filePart = mock(Part.class);
    when(filePart.getSubmittedFileName()).thenReturn("bad.bin");
    when(filePart.getInputStream()).thenThrow(new IOException("disk error"));
    RequestContext reqCtx = mockReqCtx(null, mock(TraceSegment.class));
    BiFunction<RequestContext, List<String>, Flow<Void>> contentCb = mockPassThroughCb();

    assertFalse(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList(filePart), reqCtx, null, null, contentCb));

    verify(contentCb).apply(eq(reqCtx), eq(Collections.singletonList("")));
  }

  @Test
  void processPartsAndBlock_filenamesCbBlocks_contentCbNotFired() throws Exception {
    Part filePart = mockPart("evil.exe", "application/octet-stream", "content".getBytes());
    TraceSegment segment = mock(TraceSegment.class);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    RequestContext reqCtx = mockReqCtx(brf, segment);
    BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb = mockBlockingCb(403);
    BiFunction<RequestContext, List<String>, Flow<Void>> contentCb = mockPassThroughCb();

    assertTrue(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList(filePart), reqCtx, null, filenamesCb, contentCb));

    verify(contentCb, never()).apply(any(), any());
  }

  @Test
  void processPartsAndBlock_contentCbBlocks_returnsTrue() throws Exception {
    Part filePart = mockPart("upload.bin", "application/octet-stream", "payload".getBytes());
    TraceSegment segment = mock(TraceSegment.class);
    BlockResponseFunction brf = mock(BlockResponseFunction.class);
    RequestContext reqCtx = mockReqCtx(brf, segment);
    BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb = mockPassThroughCb();
    BiFunction<RequestContext, List<String>, Flow<Void>> contentCb = mockBlockingCb(403);

    assertTrue(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList(filePart), reqCtx, null, filenamesCb, contentCb));
  }

  @Test
  void processPartsAndBlock_nonPartObject_skipped() {
    RequestContext reqCtx = mockReqCtx(null, mock(TraceSegment.class));
    BiFunction<RequestContext, List<String>, Flow<Void>> filenamesCb = mockPassThroughCb();

    assertFalse(
        GlassFishBlockingHelper.processPartsAndBlock(
            Collections.singletonList("not-a-part"), reqCtx, null, filenamesCb, null));

    verify(filenamesCb, never()).apply(any(), any());
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

  private static Part mockPart(String submittedFilename, String contentType, byte[] content)
      throws Exception {
    Part part = mock(Part.class);
    when(part.getSubmittedFileName()).thenReturn(submittedFilename);
    when(part.getContentType()).thenReturn(contentType);
    when(part.getInputStream()).thenAnswer(ignored -> new java.io.ByteArrayInputStream(content));
    return part;
  }

  @SuppressWarnings("unchecked")
  private static BiFunction<RequestContext, List<String>, Flow<Void>> mockPassThroughCb() {
    BiFunction<RequestContext, List<String>, Flow<Void>> cb = mock(BiFunction.class);
    Flow<Void> flow = mock(Flow.class);
    when(flow.getAction()).thenReturn(Flow.Action.Noop.INSTANCE);
    when(cb.apply(any(), any())).thenReturn(flow);
    return cb;
  }

  @SuppressWarnings("unchecked")
  private static BiFunction<RequestContext, List<String>, Flow<Void>> mockBlockingCb(
      int statusCode) {
    BiFunction<RequestContext, List<String>, Flow<Void>> cb = mock(BiFunction.class);
    Flow<Void> flow = mock(Flow.class);
    when(flow.getAction())
        .thenReturn(new Flow.Action.RequestBlockingAction(statusCode, BlockingContentType.AUTO));
    when(cb.apply(any(), any())).thenReturn(flow);
    return cb;
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
