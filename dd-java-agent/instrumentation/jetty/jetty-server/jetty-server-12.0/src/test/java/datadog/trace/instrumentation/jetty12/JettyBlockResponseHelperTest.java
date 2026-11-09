package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyFakes.proxy;
import static datadog.trace.instrumentation.jetty12.JettyFakes.toBytes;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/** Unit tests for {@link JettyBlockResponseHelper} and {@link JettyBlockResponseFunction}. */
class JettyBlockResponseHelperTest {

  @TableTest({
    "Scenario            | Content Type | Accept                             | Expected Type",
    "json                | JSON         | text/html                          | JSON         ",
    "html                | HTML         | application/json                   | HTML         ",
    "auto without accept | AUTO         |                                    | JSON         ",
    "auto prefers html   | AUTO         | text/html                          | HTML         ",
    "auto prefers json   | AUTO         | 'text/html;q=0.5,application/json' | JSON         "
  })
  void writesTemplateWithContentHeaders(
      BlockingContentType contentType, String accept, TemplateType expectedType) {
    FakeRequest request = new FakeRequest("GET", accept);
    FakeResponse response = new FakeResponse();
    response.headers.put("X-App-Header", "app");
    FakeSegment segment = new FakeSegment();

    boolean committed =
        JettyBlockResponseHelper.commitBlockingResponse(
            segment.proxy,
            request.proxy,
            response.proxy,
            418,
            contentType,
            singletonMap("X-Extra", "value"),
            "rid-1");

    assertTrue(committed);
    byte[] expectedBody = BlockingActionHelper.getTemplate(expectedType, "rid-1");
    assertEquals(Boolean.TRUE, request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(1, response.resets);
    assertEquals(418, response.status);
    assertNull(response.headers.get("X-App-Header"), "reset must clear previous headers");
    assertEquals("value", response.headers.get("X-Extra"));
    assertEquals(
        BlockingActionHelper.getContentType(expectedType),
        response.headers.get(HttpHeader.CONTENT_TYPE));
    assertEquals(
        String.valueOf(expectedBody.length), response.headers.get(HttpHeader.CONTENT_LENGTH));
    assertEquals(1, response.writes.size());
    assertTrue(response.lastFlags.get(0));
    assertArrayEquals(expectedBody, response.writes.get(0));
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void invalidStatusFallsBackToDefault() {
    FakeResponse response = new FakeResponse();
    assertTrue(commit(new FakeRequest("GET", null), response, new FakeSegment(), 42));
    assertEquals(403, response.status);
  }

  @Test
  void noneKeepsOnlyStatusAndExtraHeaders() {
    FakeResponse response = new FakeResponse();
    FakeSegment segment = new FakeSegment();

    boolean committed =
        JettyBlockResponseHelper.commitBlockingResponse(
            segment.proxy,
            new FakeRequest("GET", "text/html").proxy,
            response.proxy,
            301,
            BlockingContentType.NONE,
            singletonMap("Location", "https://example.com/"),
            null);

    assertTrue(committed);
    assertEquals(301, response.status);
    assertEquals("https://example.com/", response.headers.get("Location"));
    assertEquals(1, response.headers.size());
    assertEquals(1, response.writes.size());
    assertEquals(0, response.writes.get(0).length);
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void headRequestHasNoBody() {
    FakeResponse response = new FakeResponse();
    assertTrue(commit(new FakeRequest("HEAD", "application/json"), response, new FakeSegment()));
    assertEquals(403, response.status);
    assertNull(response.headers.get(HttpHeader.CONTENT_TYPE));
    assertNull(response.headers.get(HttpHeader.CONTENT_LENGTH));
    assertEquals(0, response.writes.get(0).length);
  }

  @Test
  void prepareReturnsNullWhenCommitted() {
    FakeRequest request = new FakeRequest("GET", null);
    FakeResponse response = new FakeResponse();
    response.committed = true;

    assertNull(
        JettyBlockResponseHelper.prepareBlockResponse(
            request.proxy, response.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(0, response.resets);
  }

  @Test
  void prepareDoesNotWrite() {
    FakeResponse response = new FakeResponse();
    ByteBuffer body =
        JettyBlockResponseHelper.prepareBlockResponse(
            new FakeRequest("GET", null).proxy,
            response.proxy,
            403,
            BlockingContentType.JSON,
            emptyMap(),
            null);

    assertArrayEquals(BlockingActionHelper.getTemplate(TemplateType.JSON, null), toBytes(body));
    assertTrue(response.writes.isEmpty());
  }

  @TableTest({
    "Scenario     | Failure      | Resets | Writes",
    "committed    | committed    | 0      | 0     ",
    "reset throws | reset        | 0      | 0     ",
    "write fails  | write fails  | 1      | 1     ",
    "write throws | write throws | 1      | 0     "
  })
  void failedCommitIsNotBlocked(String failure, int resets, int writes) {
    FakeResponse response = new FakeResponse();
    switch (failure) {
      case "committed":
        response.committed = true;
        break;
      case "reset":
        response.resetFailure = new IllegalStateException("committed");
        break;
      case "write fails":
        response.writeFailure = new IOException("broken pipe");
        break;
      case "write throws":
        response.writeThrows = new WritePendingException();
        break;
      default:
        throw new IllegalArgumentException(failure);
    }
    FakeSegment segment = new FakeSegment();

    assertFalse(commit(new FakeRequest("GET", null), response, segment));
    assertEquals(resets, response.resets);
    assertEquals(writes, response.writes.size());
    assertEquals(0, segment.effectivelyBlockedCalls);
  }

  @Test
  void templateFailureLeavesResponseUntouched() {
    FakeRequest request = new FakeRequest("GET", null);
    request.headersFailure = new IllegalStateException("headers");
    FakeResponse response = new FakeResponse();

    assertThrows(
        IllegalStateException.class,
        () ->
            JettyBlockResponseHelper.prepareBlockResponse(
                request.proxy, response.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    assertNull(request.attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertEquals(0, response.resets);
  }

  @Test
  void interruptedWaitKeepsInterruptFlagAndMarksOnLateSuccess() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    FakeSegment segment = new FakeSegment();

    Thread.currentThread().interrupt();
    try {
      assertTrue(commit(new FakeRequest("GET", null), response, segment));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(0, segment.effectivelyBlockedCalls);

    response.pending.succeeded();

    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void timedOutWriteIsInitiatedAndMarksOnLateSuccess() {
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    FakeSegment segment = new FakeSegment();

    assertTrue(commitWithShortTimeout(new FakeRequest("GET", null), response, segment));
    assertEquals(0, segment.effectivelyBlockedCalls);

    response.pending.succeeded();

    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void timedOutWriteReportsBlockFailureOnLateFailure() {
    FakeRequest request = new FakeRequest("GET", null);
    int[] blockFailures = new int[1];
    request.attributes.put(DD_CONTEXT_ATTRIBUTE, context(blockFailures));
    FakeResponse response = new FakeResponse();
    response.completeWrites = false;
    FakeSegment segment = new FakeSegment();

    assertTrue(commitWithShortTimeout(request, response, segment));

    response.pending.failed(new IOException("broken pipe"));

    assertEquals(0, segment.effectivelyBlockedCalls);
    assertEquals(1, blockFailures[0]);
  }

  @Test
  void effectivelyBlockedFailureKeepsSuccess() {
    FakeSegment segment = new FakeSegment();
    segment.failure = new IllegalStateException("segment finished");

    assertTrue(commit(new FakeRequest("GET", null), new FakeResponse(), segment));
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void functionShortCircuitsAfterSuccessfulCommit() {
    FakeResponse response = new FakeResponse();
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf =
        new JettyBlockResponseFunction(new FakeRequest("GET", null).proxy, response.proxy);

    assertTrue(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    response.committed = true;
    assertTrue(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));

    assertEquals(1, response.writes.size());
    assertEquals(1, response.resets);
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  @Test
  void functionRetriesAfterFailedCommit() {
    FakeResponse response = new FakeResponse();
    response.writeFailure = new IOException("broken pipe");
    FakeSegment segment = new FakeSegment();
    JettyBlockResponseFunction brf =
        new JettyBlockResponseFunction(new FakeRequest("GET", null).proxy, response.proxy);

    assertFalse(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));
    response.writeFailure = null;
    assertTrue(
        brf.tryCommitBlockingResponse(
            segment.proxy, 403, BlockingContentType.JSON, emptyMap(), null));

    assertEquals(2, response.writes.size());
    assertEquals(1, segment.effectivelyBlockedCalls);
  }

  private static boolean commit(FakeRequest request, FakeResponse response, FakeSegment segment) {
    return commit(request, response, segment, 403);
  }

  private static boolean commit(
      FakeRequest request, FakeResponse response, FakeSegment segment, int status) {
    return JettyBlockResponseHelper.commitBlockingResponse(
        segment.proxy,
        request.proxy,
        response.proxy,
        status,
        BlockingContentType.AUTO,
        emptyMap(),
        null);
  }

  private static boolean commitWithShortTimeout(
      FakeRequest request, FakeResponse response, FakeSegment segment) {
    return JettyBlockResponseHelper.commitBlockingResponse(
        segment.proxy,
        request.proxy,
        response.proxy,
        403,
        BlockingContentType.AUTO,
        emptyMap(),
        null,
        1);
  }

  private static Context context(int[] blockFailures) {
    AppSecContext appSecCtx =
        proxy(
            AppSecContext.class,
            (name, args) -> {
              if ("reportBlockFailure".equals(name)) {
                blockFailures[0]++;
              }
              return null;
            });
    RequestContext reqCtx =
        proxy(
            RequestContext.class,
            (name, args) ->
                "getData".equals(name) && args[0] == RequestContextSlot.APPSEC ? appSecCtx : null);
    AgentSpan span =
        proxy(AgentSpan.class, (name, args) -> "getRequestContext".equals(name) ? reqCtx : null);
    return proxy(Context.class, (name, args) -> "get".equals(name) ? span : null);
  }

  private static final class FakeRequest {
    final String method;
    final HttpFields headers;
    final Map<String, Object> attributes = new HashMap<>();
    final Request proxy;
    RuntimeException headersFailure;

    FakeRequest(String method, String accept) {
      this.method = method;
      HttpFields.Mutable fields = HttpFields.build();
      if (accept != null) {
        fields.put(HttpHeader.ACCEPT, accept);
      }
      this.headers = fields.asImmutable();
      this.proxy =
          proxy(
              Request.class,
              (name, args) -> {
                switch (name) {
                  case "getMethod":
                    return this.method;
                  case "getHeaders":
                    if (headersFailure != null) {
                      throw headersFailure;
                    }
                    return headers;
                  case "setAttribute":
                    return attributes.put((String) args[0], args[1]);
                  case "getAttribute":
                    return attributes.get((String) args[0]);
                  default:
                    throw new UnsupportedOperationException(name);
                }
              });
    }
  }

  private static final class FakeResponse {
    final HttpFields.Mutable headers = HttpFields.build();
    final List<byte[]> writes = new ArrayList<>();
    final List<Boolean> lastFlags = new ArrayList<>();
    final Response proxy;
    int status;
    int resets;
    boolean committed;
    boolean completeWrites = true;
    Callback pending;
    RuntimeException resetFailure;
    RuntimeException writeThrows;
    Throwable writeFailure;

    FakeResponse() {
      this.proxy =
          proxy(
              Response.class,
              (name, args) -> {
                switch (name) {
                  case "isCommitted":
                    return committed;
                  case "getStatus":
                    return status;
                  case "setStatus":
                    status = (Integer) args[0];
                    return null;
                  case "getHeaders":
                    return headers;
                  case "reset":
                    if (resetFailure != null) {
                      throw resetFailure;
                    }
                    resets++;
                    status = 0;
                    headers.clear();
                    return null;
                  case "write":
                    if (writeThrows != null) {
                      throw writeThrows;
                    }
                    lastFlags.add((Boolean) args[0]);
                    writes.add(toBytes((ByteBuffer) args[1]));
                    Callback callback = (Callback) args[2];
                    if (!completeWrites) {
                      pending = callback;
                      return null;
                    }
                    if (writeFailure != null) {
                      callback.failed(writeFailure);
                    } else {
                      committed = true;
                      callback.succeeded();
                    }
                    return null;
                  default:
                    throw new UnsupportedOperationException(name);
                }
              });
    }
  }

  private static final class FakeSegment {
    final TraceSegment proxy;
    int effectivelyBlockedCalls;
    RuntimeException failure;

    FakeSegment() {
      this.proxy =
          proxy(
              TraceSegment.class,
              (name, args) -> {
                if ("effectivelyBlocked".equals(name)) {
                  effectivelyBlockedCalls++;
                  if (failure != null) {
                    throw failure;
                  }
                  return null;
                }
                throw new UnsupportedOperationException(name);
              });
    }
  }
}
