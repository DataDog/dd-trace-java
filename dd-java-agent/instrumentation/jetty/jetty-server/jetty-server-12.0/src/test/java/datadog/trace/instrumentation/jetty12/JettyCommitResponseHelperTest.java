package datadog.trace.instrumentation.jetty12;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_IGNORE_COMMIT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty12.JettyFakes.fake;
import static datadog.trace.instrumentation.jetty12.JettyFakes.proxy;
import static datadog.trace.instrumentation.jetty12.JettyFakes.toBytes;
import static java.util.Collections.singletonMap;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.api.appsec.AppSecContext;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * Unit tests for {@link JettyCommitResponseHelper}.
 *
 * <p>Uses dynamic proxy fakes so the tests compile against every Jetty 12 version. The response IG
 * callbacks are replaced by a {@link JettyDecorator} subclass.
 */
class JettyCommitResponseHelperTest {

  private static final Flow.Action.RequestBlockingAction RBA =
      new Flow.Action.RequestBlockingAction(
          413, BlockingContentType.JSON, singletonMap("X-Extra", "value"), "rid-1");

  private final Map<String, Object> attributes = new HashMap<>();
  private final HttpFields.Mutable headers = HttpFields.build();
  private final List<ByteBuffer> writes = new ArrayList<>();
  private final List<Callback> writeCallbacks = new ArrayList<>();
  private final FakeDecorator decorator = new FakeDecorator();
  private BlockResponseFunction brf;
  private boolean committed;
  private int status;
  private int resets;
  private RuntimeException resetFailure;
  private RuntimeException writeFailure;
  private boolean completeWritesInline;
  private int effectivelyBlocked;
  private int blockFailures;
  private int originalSucceeded;
  private Throwable originalFailure;

  private final TraceSegment segment =
      proxy(
          TraceSegment.class,
          (name, args) -> {
            if ("effectivelyBlocked".equals(name)) {
              effectivelyBlocked++;
            }
            return null;
          });

  private final Callback original =
      new Callback() {
        @Override
        public void succeeded() {
          originalSucceeded++;
        }

        @Override
        public void failed(Throwable x) {
          originalFailure = x;
        }

        @Override
        public InvocationType getInvocationType() {
          return NON_BLOCKING;
        }
      };

  @TableTest({
    "Scenario           | Context | Span  | Request Context | Committed | Ignore Commit | Status",
    "committed          | true    | true  | true            | true      | false         | 0     ",
    "ignore attribute   | true    | true  | true            | false     | true          | 0     ",
    "interim status     | true    | true  | true            | false     | false         | 103   ",
    "no context         | false   | true  | true            | false     | false         | 0     ",
    "no span            | true    | false | true            | false     | false         | 0     ",
    "no request context | true    | true  | false           | false     | false         | 0     "
  })
  void nothingToEvaluate(
      boolean hasContext,
      boolean hasSpan,
      boolean hasReqCtx,
      boolean committed,
      boolean ignoreCommit,
      int status) {
    if (hasContext) {
      attributes.put(DD_CONTEXT_ATTRIBUTE, hasSpan ? context(hasReqCtx) : fake(Context.class));
    }
    if (ignoreCommit) {
      attributes.put(DD_IGNORE_COMMIT_ATTRIBUTE, Boolean.TRUE);
    }
    this.committed = committed;
    this.status = status;

    assertFalse(tryBlock());
    assertEquals(0, decorator.calls);
    assertEquals(ignoreCommit ? Boolean.TRUE : null, attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertTrue(writes.isEmpty());
  }

  @TableTest({
    "Scenario     | Status | Expected Status",
    "unset status | 0      | 200            ",
    "status set   | 404    | 404            "
  })
  void noActionLetsWriteProceed(int status, int expectedStatus) {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    this.status = status;

    assertFalse(tryBlock());
    assertEquals(1, decorator.calls);
    assertEquals(expectedStatus, decorator.status);
    assertEquals(Boolean.TRUE, attributes.get(DD_IGNORE_COMMIT_ATTRIBUTE));
    assertSame(ExtractAdapter.Response.GETTER, decorator.visitor);
    assertEquals(0, resets);
    assertTrue(writes.isEmpty());
  }

  @Test
  void blockingActionWritesBlockingResponse() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    headers.put("X-App-Header", "app");
    decorator.action = RBA;

    assertTrue(tryBlock());

    assertEquals(1, resets);
    assertEquals(413, status);
    assertNull(headers.get("X-App-Header"), "reset must clear previous headers");
    assertEquals("value", headers.get("X-Extra"));
    byte[] expectedBody = BlockingActionHelper.getTemplate(TemplateType.JSON, "rid-1");
    assertEquals(
        BlockingActionHelper.getContentType(TemplateType.JSON),
        headers.get(HttpHeader.CONTENT_TYPE));
    assertEquals(1, writes.size());
    assertArrayEquals(expectedBody, toBytes(writes.get(0)));
    Callback wrapped = writeCallbacks.get(0);
    assertInstanceOf(JettyCommitResponseHelper.BlockingCallback.class, wrapped);
    assertEquals(NON_BLOCKING, wrapped.getInvocationType());
    assertEquals(0, effectivelyBlocked, "marked only once the write completes");
    assertEquals(0, originalSucceeded);
  }

  @Test
  void wrappedCallbackSuccessMarksBlocked() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    assertTrue(tryBlock());

    writeCallbacks.get(0).succeeded();

    assertEquals(1, effectivelyBlocked);
    assertEquals(1, originalSucceeded);
    assertNull(originalFailure);
    assertEquals(0, blockFailures);
  }

  @Test
  void wrappedCallbackFailureReportsAndFailsOriginal() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    assertTrue(tryBlock());
    IOException failure = new IOException("broken pipe");

    writeCallbacks.get(0).failed(failure);

    assertEquals(0, effectivelyBlocked);
    assertEquals(1, blockFailures);
    assertSame(failure, originalFailure);
    assertEquals(0, originalSucceeded);
  }

  @Test
  void laterBrfBlockAfterResponseBlockIsNotReportedAsFailure() {
    Response response = response();
    JettyBlockResponseFunction jettyBrf =
        new JettyBlockResponseFunction(response.getRequest(), response);
    brf = jettyBrf;
    Context context = context(true);
    attributes.put(DD_CONTEXT_ATTRIBUTE, context);
    decorator.action = RBA;
    assertTrue(tryBlock());
    // the blocking write is still in flight and has committed the response
    committed = true;
    RequestContext reqCtx = AgentSpan.fromContext(context).getRequestContext();

    assertTrue(jettyBrf.tryCommitBlockingResponse(reqCtx, RBA));

    assertEquals(1, writes.size(), "no second blocking write");
    assertEquals(0, blockFailures);
    assertEquals(0, effectivelyBlocked, "marked only once the write completes");
  }

  @Test
  void brfCalledFromAnInlineWriteCompletionIsNotReportedAsFailure() {
    Response response = response();
    JettyBlockResponseFunction jettyBrf =
        new JettyBlockResponseFunction(response.getRequest(), response);
    brf = jettyBrf;
    Context context = context(true);
    attributes.put(DD_CONTEXT_ATTRIBUTE, context);
    decorator.action = RBA;
    completeWritesInline = true;
    RequestContext reqCtx = AgentSpan.fromContext(context).getRequestContext();
    boolean[] laterBlock = new boolean[1];
    Callback callingBrf =
        new Callback() {
          @Override
          public void succeeded() {
            // e.g. an async WriteListener whose next step triggers a RASP block
            laterBlock[0] = jettyBrf.tryCommitBlockingResponse(reqCtx, RBA);
          }

          @Override
          public InvocationType getInvocationType() {
            return NON_BLOCKING;
          }
        };

    assertTrue(JettyCommitResponseHelper.tryBlockOnCommit(decorator, response, callingBrf));

    assertTrue(laterBlock[0], "the later evaluation sees the response-phase block");
    assertEquals(1, writes.size(), "no second blocking write");
    assertEquals(1, effectivelyBlocked);
    assertEquals(0, blockFailures);
  }

  @Test
  void synchronousWriteFailureReleasesTheClaim() {
    Response response = response();
    JettyBlockResponseFunction jettyBrf =
        new JettyBlockResponseFunction(response.getRequest(), response);
    brf = jettyBrf;
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    writeFailure = new IllegalStateException("write pending");

    assertFalse(JettyCommitResponseHelper.tryBlockOnCommit(decorator, response, original));

    assertFalse(jettyBrf.isBlockingResponseInitiated(), "nothing was accepted");
    assertEquals(1, blockFailures);
  }

  @Test
  void asynchronousWriteFailureKeepsTheClaim() {
    Response response = response();
    JettyBlockResponseFunction jettyBrf =
        new JettyBlockResponseFunction(response.getRequest(), response);
    brf = jettyBrf;
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;

    assertTrue(JettyCommitResponseHelper.tryBlockOnCommit(decorator, response, original));
    writeCallbacks.get(0).failed(new IOException("client gone"));

    assertTrue(jettyBrf.isBlockingResponseInitiated(), "later callers still abort");
    assertEquals(1, blockFailures);
  }

  @Test
  void nullOriginalCallbackIsTolerated() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;

    assertTrue(JettyCommitResponseHelper.tryBlockOnCommit(decorator, response(), null));
    writeCallbacks.get(0).succeeded();

    assertEquals(1, effectivelyBlocked);
  }

  @Test
  void resetFailureFailsOpenAndReports() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    resetFailure = new IllegalStateException("committed");

    assertFalse(tryBlock());
    assertTrue(writes.isEmpty());
    assertEquals(1, blockFailures);
  }

  @Test
  void committedDuringEvaluationFailsOpenAndReports() {
    attributes.put(DD_CONTEXT_ATTRIBUTE, context(true));
    decorator.action = RBA;
    decorator.commitOnCall = true;

    assertFalse(tryBlock());
    assertEquals(0, resets);
    assertTrue(writes.isEmpty());
    assertEquals(1, blockFailures);
  }

  private boolean tryBlock() {
    return JettyCommitResponseHelper.tryBlockOnCommit(decorator, response(), original);
  }

  private Response response() {
    Request request =
        proxy(
            Request.class,
            (name, args) -> {
              switch (name) {
                case "getAttribute":
                  return attributes.get((String) args[0]);
                case "setAttribute":
                  return attributes.put((String) args[0], args[1]);
                case "getMethod":
                  return "GET";
                case "getHeaders":
                  return HttpFields.EMPTY;
                default:
                  throw new UnsupportedOperationException(name);
              }
            });
    return proxy(
        Response.class,
        (name, args) -> {
          switch (name) {
            case "isCommitted":
              return committed;
            case "getRequest":
              return request;
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
              if (writeFailure != null) {
                throw writeFailure;
              }
              writes.add((ByteBuffer) args[1]);
              writeCallbacks.add((Callback) args[2]);
              committed = true;
              if (completeWritesInline) {
                ((Callback) args[2]).succeeded();
              }
              return null;
            default:
              throw new UnsupportedOperationException(name);
          }
        });
  }

  private Context context(boolean hasReqCtx) {
    AppSecContext appSecCtx =
        proxy(
            AppSecContext.class,
            (name, args) -> {
              if ("reportBlockFailure".equals(name)) {
                blockFailures++;
              }
              return null;
            });
    RequestContext reqCtx =
        proxy(
            RequestContext.class,
            (name, args) -> {
              switch (name) {
                case "getTraceSegment":
                  return segment;
                case "getBlockResponseFunction":
                  return brf;
                case "getData":
                  return args[0] == RequestContextSlot.APPSEC ? appSecCtx : null;
                default:
                  return null;
              }
            });
    AgentSpan span =
        proxy(
            AgentSpan.class,
            (name, args) -> "getRequestContext".equals(name) && hasReqCtx ? reqCtx : null);
    return proxy(Context.class, (name, args) -> "get".equals(name) ? span : null);
  }

  private final class FakeDecorator extends JettyDecorator {
    int calls;
    int status;
    Object visitor;
    Flow.Action action = Flow.Action.Noop.INSTANCE;
    boolean commitOnCall;

    @Override
    public <RESP> Flow<Void> callIGCallbackResponseAndHeaders(
        AgentSpan span,
        RESP carrier,
        int status,
        AgentPropagation.ContextVisitor<RESP> contextVisitor) {
      calls++;
      this.status = status;
      this.visitor = contextVisitor;
      if (commitOnCall) {
        committed = true;
      }
      Flow.Action result = action;
      return new Flow<Void>() {
        @Override
        public Action getAction() {
          return result;
        }

        @Override
        public Void getResult() {
          return null;
        }
      };
    }
  }
}
