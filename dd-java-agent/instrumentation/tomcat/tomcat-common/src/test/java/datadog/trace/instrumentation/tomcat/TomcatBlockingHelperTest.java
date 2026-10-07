package datadog.trace.instrumentation.tomcat;

import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.servlet.ServletOutputStream;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link TomcatBlockingHelper#commitBlockingResponse}'s block-failure reporting: the {@code
 * GET_OUTPUT_STREAM == null} degraded path (the JVM-wide reflective lookup of {@code
 * Response#getOutputStream()} failed at class initialization), the "response already committed by
 * something other than us" path, and the "write threw" path. In all three, a blocking commit was
 * genuinely attempted and failed, so a {@code block_failure} must be reported on the AppSec context
 * reachable from the Tomcat request.
 *
 * <p>On a real classpath {@code Response#getOutputStream()} always exists, so the field is never
 * {@code null} and the degraded-lookup branch is unreachable by normal means. There is no
 * production test hook (and adding one is out of scope), so the tests temporarily overwrite the
 * {@code private static final} field and restore it afterwards. {@code Field#set} refuses static
 * finals even after {@code setAccessible(true)}, and {@code Lookup#unreflectVarHandle} refuses
 * write access to them as well, so the write goes through {@code sun.misc.Unsafe}, accessed purely
 * reflectively (the same style already used in {@code dd-smoke-tests/crashtracking}).
 */
class TomcatBlockingHelperTest {

  private static final Field FIELD;
  private static final MethodHandle ORIGINAL_VALUE;
  private static final Object UNSAFE;
  private static final Method PUT_OBJECT;
  private static final Object STATIC_BASE;
  private static final long STATIC_OFFSET;

  static {
    try {
      FIELD = TomcatBlockingHelper.class.getDeclaredField("GET_OUTPUT_STREAM");
      FIELD.setAccessible(true);
      // also forces class initialization, so the static initializer cannot overwrite our writes
      ORIGINAL_VALUE = (MethodHandle) FIELD.get(null);

      Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
      Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      UNSAFE = theUnsafe.get(null);
      STATIC_BASE = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(UNSAFE, FIELD);
      STATIC_OFFSET =
          (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(UNSAFE, FIELD);
      PUT_OBJECT = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static void setLookup(MethodHandle value) {
    try {
      PUT_OBJECT.invoke(UNSAFE, STATIC_BASE, STATIC_OFFSET, value);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to overwrite GET_OUTPUT_STREAM", e);
    }
  }

  @AfterEach
  void restoreLookup() {
    setLookup(ORIGINAL_VALUE);
  }

  @Test
  void sanityCheck_lookupSucceedsOnARealClasspath() {
    assertNotNull(ORIGINAL_VALUE);
  }

  @Test
  void commitBlockingResponse_lookupFailed_reportsBlockFailureAndReturnsFalse() {
    setLookup(null);

    AppSecContext appSecCtx = mock(AppSecContext.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
    Request request = mockRequestWithSpan(mockSpan(reqCtx));
    TraceSegment segment = mock(TraceSegment.class);

    assertFalse(commitBlockingResponse(segment, request));

    verify(appSecCtx).reportBlockFailure();
    verifyNoInteractions(segment);
  }

  @Test
  void commitBlockingResponse_lookupFailed_noDatadogContextOnRequest_doesNotReport() {
    setLookup(null);

    Request request = mock(Request.class);
    when(request.getAttribute(HttpServerDecorator.DD_CONTEXT_ATTRIBUTE)).thenReturn(null);
    TraceSegment segment = mock(TraceSegment.class);

    assertFalse(commitBlockingResponse(segment, request));

    verifyNoInteractions(segment);
  }

  @Test
  void commitBlockingResponse_lookupFailed_noSpanInContext_doesNotReport() {
    setLookup(null);

    Request request = mockRequestWithSpan(null);

    assertFalse(commitBlockingResponse(mock(TraceSegment.class), request));
  }

  @Test
  void commitBlockingResponse_lookupFailed_noRequestContextOnSpan_doesNotReport() {
    setLookup(null);

    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(null);

    assertFalse(commitBlockingResponse(mock(TraceSegment.class), mockRequestWithSpan(span)));
  }

  @Test
  void commitBlockingResponse_lookupFailed_appsecDataIsNotAnAppSecContext_doesNotReport() {
    setLookup(null);

    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn("not an AppSecContext");
    Request request = mockRequestWithSpan(mockSpan(reqCtx));

    assertFalse(commitBlockingResponse(mock(TraceSegment.class), request));

    verify(reqCtx).getData(RequestContextSlot.APPSEC);
  }

  @Test
  void commitBlockingResponse_lookupFailed_doesNotTouchTheResponse() {
    setLookup(null);

    Request request = mock(Request.class);

    assertFalse(commitBlockingResponse(mock(TraceSegment.class), request));

    // the early return happens before any status/header/attribute mutation
    verify(request, never()).setAttribute(any(), any());
  }

  @Test
  void commitBlockingResponse_alreadyCommittedByApplication_reportsBlockFailureAndReturnsTrue() {
    AppSecContext appSecCtx = mock(AppSecContext.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
    Request request = mockRequestWithSpan(mockSpan(reqCtx));
    Response resp = mockResponse(request, true);
    TraceSegment segment = mock(TraceSegment.class);

    assertTrue(commitBlockingResponse(segment, request, resp));

    verify(appSecCtx).reportBlockFailure();
    verifyNoInteractions(segment);
    verify(resp, never()).reset();
  }

  @Test
  void commitBlockingResponse_outputStreamThrows_reportsBlockFailureAndReturnsTrue()
      throws IOException {
    AppSecContext appSecCtx = mock(AppSecContext.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
    Request request = mockRequestWithSpan(mockSpan(reqCtx));
    Response resp = mockResponse(request, false);
    when(resp.getOutputStream()).thenThrow(new IOException("boom"));
    TraceSegment segment = mock(TraceSegment.class);

    assertTrue(commitBlockingResponse(segment, request, resp));

    verify(appSecCtx).reportBlockFailure();
    verify(segment, never()).effectivelyBlocked();
  }

  @Test
  void commitBlockingResponse_outputStreamIllegalState_writerAlsoThrows_reportsBlockFailure()
      throws IOException {
    AppSecContext appSecCtx = mock(AppSecContext.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
    Request request = mockRequestWithSpan(mockSpan(reqCtx));
    Response resp = mockResponse(request, false);
    when(resp.getOutputStream()).thenThrow(new IllegalStateException());
    when(resp.getWriter()).thenThrow(new IOException("boom"));
    TraceSegment segment = mock(TraceSegment.class);

    assertTrue(commitBlockingResponse(segment, request, resp));

    verify(appSecCtx).reportBlockFailure();
  }

  @Test
  void commitBlockingResponse_writeSucceeds_doesNotReportAndMarksSegmentBlocked()
      throws IOException {
    AppSecContext appSecCtx = mock(AppSecContext.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
    Request request = mockRequestWithSpan(mockSpan(reqCtx));
    Response resp = mockResponse(request, false);
    when(resp.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
    TraceSegment segment = mock(TraceSegment.class);

    assertTrue(commitBlockingResponse(segment, request, resp));

    verify(segment).effectivelyBlocked();
    verify(appSecCtx, never()).reportBlockFailure();
  }

  @Test
  void commitBlockingResponse_effectivelyBlockedThrows_doesNotReportBlockFailure()
      throws IOException {
    AppSecContext appSecCtx = mock(AppSecContext.class);
    RequestContext reqCtx = mock(RequestContext.class);
    when(reqCtx.getData(RequestContextSlot.APPSEC)).thenReturn(appSecCtx);
    Request request = mockRequestWithSpan(mockSpan(reqCtx));
    Response resp = mockResponse(request, false);
    when(resp.getOutputStream()).thenReturn(mock(ServletOutputStream.class));
    TraceSegment segment = mock(TraceSegment.class);
    doThrow(new RuntimeException("span already finished")).when(segment).effectivelyBlocked();

    assertTrue(commitBlockingResponse(segment, request, resp));

    verify(appSecCtx, never()).reportBlockFailure();
  }

  private static boolean commitBlockingResponse(TraceSegment segment, Request request) {
    // the response is never touched on this path, hence null
    return TomcatBlockingHelper.commitBlockingResponse(
        segment, request, null, 403, BlockingContentType.AUTO, emptyMap(), null);
  }

  private static boolean commitBlockingResponse(
      TraceSegment segment, Request request, Response resp) {
    return TomcatBlockingHelper.commitBlockingResponse(
        segment, request, resp, 403, BlockingContentType.AUTO, emptyMap(), null);
  }

  private static AgentSpan mockSpan(RequestContext reqCtx) {
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(reqCtx);
    return span;
  }

  private static Request mockRequestWithSpan(AgentSpan span) {
    Context context = mock(Context.class);
    when(context.get(any())).thenReturn(span);
    Request request = mock(Request.class);
    when(request.getAttribute(HttpServerDecorator.DD_CONTEXT_ATTRIBUTE)).thenReturn(context);
    return request;
  }

  private static Response mockResponse(Request request, boolean committed) {
    Response resp = mock(Response.class);
    when(resp.isCommitted()).thenReturn(committed);
    when(resp.getRequest()).thenReturn(request);
    return resp;
  }
}
