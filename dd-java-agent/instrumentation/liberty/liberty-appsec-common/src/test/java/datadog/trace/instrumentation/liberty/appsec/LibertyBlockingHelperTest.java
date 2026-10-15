package datadog.trace.instrumentation.liberty.appsec;

import static datadog.trace.api.gateway.Events.EVENTS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ibm.ws.http.channel.internal.inbound.HttpInboundServiceContextImpl;
import com.ibm.wsspi.bytebuffer.WsByteBuffer;
import com.ibm.wsspi.genericbnf.HeaderField;
import com.ibm.wsspi.http.channel.HttpRequestMessage;
import com.ibm.wsspi.http.channel.HttpResponseMessage;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LibertyBlockingHelperTest {

  private TracerAPI previousTracer;
  private TracerAPI tracer;
  private CallbackProvider cbp;
  private BiFunction<RequestContext, Integer, Flow<Void>> respStarted;
  private TriConsumer<RequestContext, String, String> headerCb;
  private Function<RequestContext, Flow<Void>> headersDone;
  private Flow<Void> flow;

  private HttpInboundServiceContextImpl thiz;
  private HttpResponseMessage response;
  private HttpRequestMessage request;
  private AgentSpan span;
  private RequestContext reqCtx;
  private TraceSegment segment;
  private AppSecContext appSecCtx;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    previousTracer = AgentTracer.get();
    tracer = mock(TracerAPI.class);
    AgentTracer.forceRegister(tracer);

    cbp = mock(CallbackProvider.class);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(cbp);
    respStarted = mock(BiFunction.class);
    headerCb = mock(TriConsumer.class);
    headersDone = mock(Function.class);
    flow = mock(Flow.class);
    when(cbp.getCallback(EVENTS.responseStarted())).thenReturn(respStarted);
    when(cbp.getCallback(EVENTS.responseHeader())).thenReturn(headerCb);
    when(cbp.getCallback(EVENTS.responseHeaderDone())).thenReturn(headersDone);
    when(headersDone.apply(any())).thenReturn(flow);

    thiz = mock(HttpInboundServiceContextImpl.class);
    response = mock(HttpResponseMessage.class);
    request = mock(HttpRequestMessage.class);
    when(thiz.getResponse()).thenReturn(response);
    when(thiz.getRequest()).thenReturn(request);
    when(response.getStatusCodeAsInt()).thenReturn(200);

    span = mock(AgentSpan.class);
    reqCtx = mock(RequestContext.class);
    segment = mock(TraceSegment.class);
    appSecCtx = mock(AppSecContext.class);
    when(span.getRequestContext()).thenReturn(reqCtx);
    when(reqCtx.getTraceSegment()).thenReturn(segment);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
  }

  @AfterEach
  void tearDown() {
    AgentTracer.forceRegister(previousTracer);
  }

  private static HeaderField header(String name, String value) {
    HeaderField hf = mock(HeaderField.class);
    when(hf.getName()).thenReturn(name);
    when(hf.asString()).thenReturn(value);
    return hf;
  }

  private void blockWith(Flow.Action.RequestBlockingAction rba) {
    when(flow.getAction()).thenReturn(rba);
    when(response.getAllHeaders()).thenReturn(Collections.emptyList());
    HeaderField accept = header("Accept", "application/json");
    when(request.getHeader("Accept")).thenReturn(accept);
  }

  // ---- tryCommitBlockingResponse ----

  /** Hand-written fake: Mockito mocks do not run default methods. */
  private static final class FakeBrf implements BlockResponseFunction {
    final boolean result;
    final RuntimeException toThrow;

    FakeBrf(boolean result, RuntimeException toThrow) {
      this.result = result;
      this.toThrow = toThrow;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        TraceSegment segment,
        int statusCode,
        BlockingContentType templateType,
        Map<String, String> extraHeaders,
        String securityResponseId) {
      return result;
    }

    @Override
    public boolean tryCommitBlockingResponse(
        RequestContext reqCtx, Flow.Action.RequestBlockingAction rba) {
      if (toThrow != null) {
        throw toThrow;
      }
      return result;
    }
  }

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);

  @Test
  void tryCommitReturnsBrfResult() {
    assertTrue(
        LibertyBlockingHelper.tryCommitBlockingResponse(new FakeBrf(true, null), reqCtx, RBA));
    assertFalse(
        LibertyBlockingHelper.tryCommitBlockingResponse(new FakeBrf(false, null), reqCtx, RBA));
    verifyNoInteractions(appSecCtx);
  }

  @Test
  void tryCommitReportsFailureWhenBrfThrows() {
    boolean result =
        LibertyBlockingHelper.tryCommitBlockingResponse(
            new FakeBrf(true, new IllegalStateException("boom")), reqCtx, RBA);

    assertFalse(result);
    verify(appSecCtx).reportBlockFailure();
  }

  @Test
  void tryCommitWhenBrfThrowsAndAppSecSlotIsNotAppSecContext() {
    FakeBrf brf = new FakeBrf(true, new IllegalStateException("boom"));

    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(null);
    assertFalse(LibertyBlockingHelper.tryCommitBlockingResponse(brf, reqCtx, RBA));

    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn("not an AppSecContext");
    assertFalse(LibertyBlockingHelper.tryCommitBlockingResponse(brf, reqCtx, RBA));

    verifyNoInteractions(appSecCtx);
  }

  // ---- syncBufferEnter early returns ----

  @Test
  void returnsNullWhenMessageAlreadySent() {
    when(thiz.isMessageSent()).thenReturn(true);

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    verifyNoInteractions(cbp, response);
  }

  @Test
  void returnsNullWhenHeadersAlreadySent() {
    when(thiz.headersSent()).thenReturn(true);

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    verifyNoInteractions(cbp, response);
  }

  @Test
  void returnsNullWhenNoSpanAndNoActiveSpan() {
    when(tracer.activeSpan()).thenReturn(null);

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], null));
    verifyNoInteractions(cbp);
  }

  @Test
  void usesActiveSpanWhenSpanIsNull() {
    when(tracer.activeSpan()).thenReturn(span);
    when(flow.getAction()).thenReturn(Flow.Action.Noop.INSTANCE);
    when(response.getAllHeaders()).thenReturn(Collections.emptyList());

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], null));
    verify(span).getRequestContext();
    verify(headersDone).apply(reqCtx);
  }

  @Test
  void returnsNullWhenSpanHasNoRequestContext() {
    when(span.getRequestContext()).thenReturn(null);

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    verifyNoInteractions(cbp);
  }

  @Test
  void returnsNullWhenNoCallbackProvider() {
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(null);

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    verifyNoInteractions(response);
  }

  @Test
  void returnsNullWhenHeaderCallbackMissing() {
    when(cbp.getCallback(EVENTS.responseHeader())).thenReturn(null);

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    // responseStarted is still notified before the missing callbacks are detected
    verify(respStarted).apply(reqCtx, 200);
    verify(headersDone, never()).apply(any());
  }

  @Test
  void returnsNullWhenHeadersDoneCallbackMissing() {
    when(cbp.getCallback(EVENTS.responseHeaderDone())).thenReturn(null);

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    verify(respStarted).apply(reqCtx, 200);
    verify(headerCb, never()).accept(any(), anyString(), anyString());
  }

  @Test
  void noResponseStartedCallbackIsTolerated() {
    when(cbp.getCallback(EVENTS.responseStarted())).thenReturn(null);
    when(flow.getAction()).thenReturn(Flow.Action.Noop.INSTANCE);
    when(response.getAllHeaders()).thenReturn(Collections.emptyList());

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    verify(headersDone).apply(reqCtx);
  }

  @Test
  void nonBlockingActionReportsStatusAndHeadersAndReturnsNull() throws Exception {
    when(flow.getAction()).thenReturn(Flow.Action.Noop.INSTANCE);
    when(response.getStatusCodeAsInt()).thenReturn(201);
    HeaderField a = header("A", "1");
    HeaderField b = header("B", "2");
    when(response.getAllHeaders()).thenReturn(java.util.Arrays.asList(a, b));

    assertNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));

    verify(respStarted).apply(reqCtx, 201);
    verify(headerCb).accept(reqCtx, "A", "1");
    verify(headerCb).accept(reqCtx, "B", "2");
    verify(headersDone).apply(reqCtx);
    verify(response, never()).clear();
    verify(thiz, never()).finishResponseMessage(any(WsByteBuffer[].class));
    verifyNoInteractions(segment);
  }

  // ---- blocking path ----

  @Test
  void blocksWithTemplateAndHeaders() throws Exception {
    Map<String, String> extra = new LinkedHashMap<>();
    extra.put("X-Extra", "extra-value");
    blockWith(new Flow.Action.RequestBlockingAction(418, BlockingContentType.AUTO, extra, "sid"));

    BlockingException be = LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span);

    assertNotNull(be);
    BlockingActionHelper.TemplateType type =
        BlockingActionHelper.determineTemplateType(BlockingContentType.AUTO, "application/json");
    byte[] template = BlockingActionHelper.getTemplate(type, "sid");

    verify(response).clear();
    verify(response).setStatusCode(418);
    verify(response).setHeader("X-Extra", "extra-value");
    verify(response).setHeader("Content-length", Integer.toString(template.length));
    verify(response).setHeader("Content-type", BlockingActionHelper.getContentType(type));

    ArgumentCaptor<WsByteBuffer[]> captor = ArgumentCaptor.forClass(WsByteBuffer[].class);
    verify(thiz).finishResponseMessage(captor.capture());
    WsByteBuffer[] buffers = captor.getValue();
    assertEquals(1, buffers.length);
    assertInstanceOf(LibertyBlockingHelper.WsByteBufferImpl.class, buffers[0]);
    byte[] actual = new byte[buffers[0].remaining()];
    buffers[0].get(actual);
    assertArrayEquals(template, actual);

    verify(segment).effectivelyBlocked();
    verify(span).addThrowable(be);
    verify(span).setTag(Tags.HTTP_STATUS, 418);
    verify(appSecCtx, never()).reportBlockFailure();
  }

  @Test
  void blocksWithContentTypeNoneUsesEmptyBufferArray() throws Exception {
    blockWith(new Flow.Action.RequestBlockingAction(302, BlockingContentType.NONE));

    BlockingException be = LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span);

    assertNotNull(be);
    verify(response).setStatusCode(302);
    verify(response, never()).setHeader(eq("Content-length"), anyString());
    verify(response, never()).setHeader(eq("Content-type"), anyString());
    verify(request, never()).getHeader(anyString());

    ArgumentCaptor<WsByteBuffer[]> captor = ArgumentCaptor.forClass(WsByteBuffer[].class);
    verify(thiz).finishResponseMessage(captor.capture());
    assertEquals(0, captor.getValue().length);

    verify(segment).effectivelyBlocked();
    verify(span).setTag(Tags.HTTP_STATUS, 302);
  }

  // ---- commit failure ----

  @Test
  void finishResponseMessageFailureReportsBlockFailureAndStillBlocks() throws Exception {
    blockWith(new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO));
    doThrow(new IllegalStateException("commit failed"))
        .when(thiz)
        .finishResponseMessage(any(WsByteBuffer[].class));

    BlockingException be = LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span);

    assertNotNull(be);
    verify(appSecCtx).reportBlockFailure();
    verify(segment).effectivelyBlocked();
    verify(span).addThrowable(be);
    verify(span).setTag(Tags.HTTP_STATUS, 403);
  }

  @Test
  void reinitFailureReportsBlockFailureAndSkipsFinish() throws Exception {
    blockWith(new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO));
    doThrow(new IllegalStateException("reinit failed")).when(thiz).reinit(any());

    BlockingException be = LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span);

    assertNotNull(be);
    verify(appSecCtx).reportBlockFailure();
    verify(thiz, never()).finishResponseMessage(any(WsByteBuffer[].class));
    verify(segment).effectivelyBlocked();
  }

  @Test
  void commitFailureWithoutAppSecContextStillBlocks() throws Exception {
    blockWith(new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO));
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(null);
    doThrow(new IllegalStateException("commit failed"))
        .when(thiz)
        .finishResponseMessage(any(WsByteBuffer[].class));

    assertNotNull(LibertyBlockingHelper.syncBufferEnter(thiz, new WsByteBuffer[0], span));
    verify(segment).effectivelyBlocked();
  }
}
