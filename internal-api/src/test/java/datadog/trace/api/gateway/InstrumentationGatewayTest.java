package datadog.trace.api.gateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class InstrumentationGatewayTest {
  private InstrumentationGateway gateway;
  private SubscriptionService ss;
  private CallbackProvider cbp;
  private RequestContext context;
  private Flow<Void> flow;
  private Callback callback;
  private Events<Object> events;

  @BeforeEach
  public void setUp() {
    gateway = new InstrumentationGateway();
    ss = gateway.getSubscriptionService(RequestContextSlot.APPSEC);
    cbp = gateway.getCallbackProvider(RequestContextSlot.APPSEC);
    context =
        new RequestContext() {
          @Override
          public void close() {}

          @Override
          public Object getData(RequestContextSlot slot) {
            return this;
          }

          @Override
          public TraceSegment getTraceSegment() {
            return TraceSegment.NoOp.INSTANCE;
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
        };
    flow = new Flow.ResultFlow<>(null);
    callback = new Callback(context, flow);
    events = Events.get();
  }

  @Test
  public void testGetCallback() {
    ss.registerCallback(events.requestStarted(), callback);
    // check event without registered callback
    assertNull(cbp.getCallback(events.requestEnded()));
    // check event with registered callback
    Supplier<Flow<Object>> cback = cbp.getCallback(events.requestStarted());
    assertEquals(cback, callback);
    Flow<Object> flow = cback.get();
    assertEquals(Flow.Action.Noop.INSTANCE, flow.getAction());
    assertEquals(context, flow.getResult());
  }

  @Test
  public void testRegisterCallback() {
    Subscription s1 = ss.registerCallback(events.requestStarted(), callback);
    // check event without registered callback
    assertNull(cbp.getCallback(events.requestEnded()));
    // check event with registered callback
    assertEquals(cbp.getCallback(events.requestStarted()), callback);
    // check that we can register a callback
    Callback cb = new Callback(context, flow);
    Subscription s2 = ss.registerCallback(events.requestEnded(), cb);
    assertNotNull(s2);
    assertEquals(cbp.getCallback(events.requestEnded()), cb);
    // check that we can cancel a callback
    s1.cancel();
    assertNull(cbp.getCallback(events.requestStarted()));
    // check that we didn't remove the other callback
    assertEquals(cbp.getCallback(events.requestEnded()), cb);
  }

  @Test
  public void testDoubleRegistration() {
    ss.registerCallback(events.requestStarted(), callback);
    // check event with registered callback
    assertEquals(cbp.getCallback(events.requestStarted()), callback);
    // check that we can't overwrite the callback
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> ss.registerCallback(events.requestStarted(), callback));
    assertAll(
        () -> assertTrue(ex.getMessage().startsWith("Trying to overwrite existing callback ")),
        () -> assertTrue(ex.getMessage().contains(events.requestStarted().toString())));
  }

  @Test
  public void testDoubleCancel() {
    Subscription s1 = ss.registerCallback(events.requestStarted(), callback);
    // check event with registered callback
    assertEquals(cbp.getCallback(events.requestStarted()), callback);
    // check that we can cancel a callback
    s1.cancel();
    assertNull(cbp.getCallback(events.requestStarted()));
    // check that we can cancel a callback
    s1.cancel();
    assertNull(cbp.getCallback(events.requestStarted()));
  }

  @Test
  public void testNoopAction() {
    assertFalse(Flow.Action.Noop.INSTANCE.isBlocking());
  }

  @Test
  public void testRequestBlockingAction() {
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(400, BlockingContentType.HTML);
    assertTrue(rba.isBlocking());
    assertEquals(400, rba.getStatusCode());
    assertEquals(BlockingContentType.HTML, rba.getBlockingContentType());

    rba =
        new Flow.Action.RequestBlockingAction(
            400,
            BlockingContentType.HTML,
            Collections.singletonMap("Location", "https://www.google.com/"));
    assertTrue(rba.isBlocking());
    assertEquals(400, rba.getStatusCode());
    assertEquals(BlockingContentType.HTML, rba.getBlockingContentType());
    assertEquals("https://www.google.com/", rba.getExtraHeaders().get("Location"));

    rba = Flow.Action.RequestBlockingAction.forRedirect(301, "https://www.google.com/");
    assertTrue(rba.isBlocking());
    assertEquals(301, rba.getStatusCode());
    assertEquals(BlockingContentType.NONE, rba.getBlockingContentType());
    assertEquals("https://www.google.com/", rba.getExtraHeaders().get("Location"));
  }

  @Test
  public void testNormalCalls() {
    // check that we pass through normal calls
    ss.registerCallback(events.requestStarted(), callback);
    assertEquals(context, cbp.getCallback(events.requestStarted()).get().getResult());
    ss.registerCallback(events.requestEnded(), callback);
    assertEquals(flow, cbp.getCallback(events.requestEnded()).apply(null, null));
    ss.registerCallback(events.requestHeader(), callback);
    cbp.getCallback(events.requestHeader()).accept(null, null, null);
    ss.registerCallback(events.requestHeaderDone(), callback.function);
    assertEquals(flow, cbp.getCallback(events.requestHeaderDone()).apply(null));
    ss.registerCallback(events.requestMethodUriRaw(), callback);
    assertEquals(flow, cbp.getCallback(events.requestMethodUriRaw()).apply(null, null, null));
    ss.registerCallback(events.requestPathParams(), callback);
    assertEquals(flow, cbp.getCallback(events.requestPathParams()).apply(null, null));
    ss.registerCallback(events.requestClientSocketAddress(), callback.asClientSocketAddress());
    assertEquals(
        flow, cbp.getCallback(events.requestClientSocketAddress()).apply(null, null, null));
    ss.registerCallback(events.requestInferredClientAddress(), callback);
    assertEquals(flow, cbp.getCallback(events.requestInferredClientAddress()).apply(null, null));
    ss.registerCallback(events.requestBodyStart(), callback.asRequestBodyStart());
    assertNull(cbp.getCallback(events.requestBodyStart()).apply(null, null));
    ss.registerCallback(events.requestBodyDone(), callback.asRequestBodyDone());
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.requestBodyDone()).apply(null, null).getAction());
    ss.registerCallback(events.requestBodyProcessed(), callback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.requestBodyProcessed()).apply(null, null).getAction());
    ss.registerCallback(events.responseBody(), callback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.responseBody()).apply(null, null).getAction());
    ss.registerCallback(events.grpcServerMethod(), callback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.grpcServerMethod()).apply(null, null).getAction());
    ss.registerCallback(events.grpcServerRequestMessage(), callback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.grpcServerRequestMessage()).apply(null, null).getAction());
    ss.registerCallback(events.responseStarted(), callback);
    cbp.getCallback(events.responseStarted()).apply(null, null);
    ss.registerCallback(events.responseHeader(), callback);
    cbp.getCallback(events.responseHeader()).accept(null, null, null);
    ss.registerCallback(events.responseHeaderDone(), callback.function);
    cbp.getCallback(events.responseHeaderDone()).apply(null);
    ss.registerCallback(events.graphqlServerRequestMessage(), callback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.graphqlServerRequestMessage()).apply(null, null).getAction());
    ss.registerCallback(events.databaseConnection(), callback);
    cbp.getCallback(events.databaseConnection()).accept(null, null);
    ss.registerCallback(events.databaseSqlQuery(), callback);
    cbp.getCallback(events.databaseSqlQuery()).apply(null, null);
    ss.registerCallback(events.httpClientRequest(), callback);
    cbp.getCallback(events.httpClientRequest()).apply(null, null);
    ss.registerCallback(events.httpClientResponse(), callback);
    cbp.getCallback(events.httpClientResponse()).apply(null, null);
    ss.registerCallback(events.httpClientSampling(), callback);
    cbp.getCallback(events.httpClientSampling()).apply(null, null);
    ss.registerCallback(events.fileLoaded(), callback);
    cbp.getCallback(events.fileLoaded()).apply(null, null);
    ss.registerCallback(events.user(), callback);
    cbp.getCallback(events.user()).apply(null, null);
    ss.registerCallback(events.loginEvent(), callback);
    cbp.getCallback(events.loginEvent()).apply(null, null, null);
    ss.registerCallback(events.requestSession(), callback);
    cbp.getCallback(events.requestSession()).apply(null, null);
    ss.registerCallback(events.execCmd(), callback);
    cbp.getCallback(events.execCmd()).apply(null, null);
    ss.registerCallback(events.shellCmd(), callback);
    cbp.getCallback(events.shellCmd()).apply(null, null);
    ss.registerCallback(events.httpRoute(), callback);
    cbp.getCallback(events.httpRoute()).accept(null, null);
    assertEquals(Events.MAX_EVENTS, callback.count);
  }

  @Test
  public void testThrowableBlocking() {
    Throwback throwback = new Throwback();
    // check that we block the thrown exceptions
    ss.registerCallback(events.requestStarted(), throwback);
    assertEquals(Flow.ResultFlow.empty(), cbp.getCallback(events.requestStarted()).get());
    ss.registerCallback(events.requestEnded(), throwback);
    assertEquals(Flow.ResultFlow.empty(), cbp.getCallback(events.requestEnded()).apply(null, null));
    ss.registerCallback(events.requestHeader(), throwback);
    cbp.getCallback(events.requestHeader()).accept(null, null, null);
    ss.registerCallback(events.requestHeaderDone(), throwback.function);
    assertEquals(Flow.ResultFlow.empty(), cbp.getCallback(events.requestHeaderDone()).apply(null));
    ss.registerCallback(events.requestMethodUriRaw(), throwback);
    assertEquals(
        Flow.ResultFlow.empty(),
        cbp.getCallback(events.requestMethodUriRaw()).apply(null, null, null));
    ss.registerCallback(events.requestPathParams(), throwback);
    assertEquals(
        Flow.ResultFlow.empty(), cbp.getCallback(events.requestPathParams()).apply(null, null));
    ss.registerCallback(events.requestClientSocketAddress(), throwback.asClientSocketAddress());
    assertEquals(
        Flow.ResultFlow.empty(),
        cbp.getCallback(events.requestClientSocketAddress()).apply(null, null, null));
    ss.registerCallback(events.requestInferredClientAddress(), throwback.asInferredClientAddress());
    assertEquals(
        Flow.ResultFlow.empty(),
        cbp.getCallback(events.requestInferredClientAddress()).apply(null, null));
    ss.registerCallback(events.requestBodyStart(), throwback.asRequestBodyStart());
    assertNull(cbp.getCallback(events.requestBodyStart()).apply(null, null));
    ss.registerCallback(events.requestBodyDone(), throwback.asRequestBodyDone());
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.requestBodyDone()).apply(null, null).getAction());
    ss.registerCallback(events.requestBodyProcessed(), throwback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.requestBodyProcessed()).apply(null, null).getAction());
    ss.registerCallback(events.responseBody(), throwback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.responseBody()).apply(null, null).getAction());
    ss.registerCallback(events.grpcServerMethod(), throwback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.grpcServerMethod()).apply(null, null).getAction());
    ss.registerCallback(events.grpcServerRequestMessage(), throwback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.grpcServerRequestMessage()).apply(null, null).getAction());
    ss.registerCallback(events.responseStarted(), throwback);
    cbp.getCallback(events.responseStarted()).apply(null, null);
    ss.registerCallback(events.responseHeader(), throwback);
    cbp.getCallback(events.responseHeader()).accept(null, null, null);
    ss.registerCallback(events.responseHeaderDone(), throwback.function);
    cbp.getCallback(events.responseHeaderDone()).apply(null);
    ss.registerCallback(events.graphqlServerRequestMessage(), throwback);
    assertEquals(
        Flow.Action.Noop.INSTANCE,
        cbp.getCallback(events.graphqlServerRequestMessage()).apply(null, null).getAction());
    ss.registerCallback(events.databaseConnection(), throwback);
    cbp.getCallback(events.databaseConnection()).accept(null, null);
    ss.registerCallback(events.databaseSqlQuery(), throwback);
    cbp.getCallback(events.databaseSqlQuery()).apply(null, null);
    ss.registerCallback(events.httpClientRequest(), throwback);
    cbp.getCallback(events.httpClientRequest()).apply(null, null);
    ss.registerCallback(events.httpClientResponse(), throwback);
    cbp.getCallback(events.httpClientResponse()).apply(null, null);
    ss.registerCallback(events.httpClientSampling(), throwback);
    cbp.getCallback(events.httpClientSampling()).apply(null, null);
    ss.registerCallback(events.fileLoaded(), throwback);
    cbp.getCallback(events.fileLoaded()).apply(null, null);
    ss.registerCallback(events.user(), throwback);
    cbp.getCallback(events.user()).apply(null, null);
    ss.registerCallback(events.loginEvent(), throwback);
    cbp.getCallback(events.loginEvent()).apply(null, null, null);
    ss.registerCallback(events.requestSession(), throwback);
    cbp.getCallback(events.requestSession()).apply(null, null);
    ss.registerCallback(events.execCmd(), throwback);
    cbp.getCallback(events.execCmd()).apply(null, null);
    ss.registerCallback(events.shellCmd(), throwback);
    cbp.getCallback(events.shellCmd()).apply(null, null);
    ss.registerCallback(events.httpRoute(), throwback);
    cbp.getCallback(events.httpRoute()).accept(null, null);
    assertEquals(Events.MAX_EVENTS, throwback.count);
  }

  @Test
  public void iastRegistryOperatesIndependently() {
    SubscriptionService ssIast = gateway.getSubscriptionService(RequestContextSlot.IAST);
    CallbackProvider cbpIast = gateway.getCallbackProvider(RequestContextSlot.IAST);

    ss.registerCallback(events.requestStarted(), callback);
    assertNull(cbpIast.getCallback(events.requestStarted()));

    ssIast.registerCallback(events.requestStarted(), callback);
    assertNotNull(cbpIast.getCallback(events.requestStarted()));
  }

  @Test
  public void resettingResetsAllSubsystems() {
    SubscriptionService ssIast = gateway.getSubscriptionService(RequestContextSlot.IAST);
    CallbackProvider cbpIast = gateway.getCallbackProvider(RequestContextSlot.IAST);

    ss.registerCallback(events.requestStarted(), callback);
    ssIast.registerCallback(events.requestStarted(), callback);

    gateway.reset();

    assertNull(cbp.getCallback(events.requestStarted()));
    assertNull(cbpIast.getCallback(events.requestStarted()));
  }

  @Test
  public void invalidRequestContextSlot() {
    SubscriptionService ss = gateway.getSubscriptionService(null);
    CallbackProvider cbp = gateway.getCallbackProvider(null);

    assertSame(SubscriptionService.SubscriptionServiceNoop.INSTANCE, ss);
    assertSame(CallbackProvider.CallbackProviderNoop.INSTANCE, cbp);
  }

  @Test
  public void universalCallbackProviderForRequestEnded() {
    SubscriptionService ssIast = gateway.getSubscriptionService(RequestContextSlot.IAST);
    final int[] count = new int[1];
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> cb =
        (requestContext, igSpanInfo) -> {
          assertSame(callback.ctx, requestContext);
          assertSame(AgentTracer.noopSpan(), igSpanInfo);
          count[0]++;
          return new Flow.ResultFlow<>(null);
        };
    ss.registerCallback(events.requestEnded(), cb);
    ssIast.registerCallback(events.requestEnded(), cb);
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> uniCb =
        gateway.getUniversalCallbackProvider().getCallback(events.requestEnded());
    Flow<Void> res = uniCb.apply(callback.ctx, AgentTracer.noopSpan());

    assertEquals(2, count[0]);
    assertNotNull(res);

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> uniCb2 =
        gateway.getUniversalCallbackProvider().getCallback(events.requestEnded());
    assertSame(uniCb2, uniCb);
  }

  @Test
  public void universalCallbackWithOnlyAppSec() {
    ss.registerCallback(events.requestEnded(), callback);

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> uniCb =
        gateway.getUniversalCallbackProvider().getCallback(events.requestEnded());
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> appSecCb =
        cbp.getCallback(events.requestEnded());
    assertSame(uniCb, appSecCb);
  }

  @Test
  public void universalCallbackWithOnlyIast() {
    SubscriptionService ssIast = gateway.getSubscriptionService(RequestContextSlot.IAST);
    CallbackProvider cbpIast = gateway.getCallbackProvider(RequestContextSlot.IAST);

    ssIast.registerCallback(events.requestEnded(), callback);

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> uniCb =
        gateway.getUniversalCallbackProvider().getCallback(events.requestEnded());
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> iastCb =
        cbpIast.getCallback(events.requestEnded());
    assertSame(uniCb, iastCb);
  }

  @Test
  public void universalCallbackWithNoCallbacks() {
    assertNull(gateway.getUniversalCallbackProvider().getCallback(events.requestEnded()));
  }

  @Test
  public void mergeFlowIdenticalFlows() {
    Flow<Void> flow = new Flow.ResultFlow<>(null);
    Flow<Void> resFlow = InstrumentationGateway.mergeFlows(flow, flow);

    assertSame(flow, resFlow);
  }

  @Test
  public void mergeFlowBlockingActionHasPriority() {
    Flow<Void> flow1 = new Flow.ResultFlow<>(null);
    Flow<Void> flow2 =
        new Flow.ResultFlow<Void>(null) {
          @Override
          public Action getAction() {
            return new Action.RequestBlockingAction(410, BlockingContentType.AUTO);
          }
        };

    Flow<Void> resFlow1 = InstrumentationGateway.mergeFlows(flow1, flow2);
    Flow<Void> resFlow2 = InstrumentationGateway.mergeFlows(flow2, flow1);

    assertTrue(resFlow1.getAction().isBlocking());
    assertTrue(resFlow2.getAction().isBlocking());
  }

  @Test
  public void mergeFlowReturnsNonNullResult() {
    Flow<Integer> flow1 = new Flow.ResultFlow<>(42);
    Flow<Integer> flow2 = new Flow.ResultFlow<>(null);

    Flow<Integer> resFlow1 = InstrumentationGateway.mergeFlows(flow1, flow2);
    Flow<Integer> resFlow2 = InstrumentationGateway.mergeFlows(flow2, flow1);

    assertEquals(42, resFlow1.getResult());
    assertEquals(42, resFlow2.getResult());
  }

  @Test
  public void mergeFlowReturnsLatestNonNullResult() {
    Flow<Integer> flow1 = new Flow.ResultFlow<>(42);
    Flow<Integer> flow2 = new Flow.ResultFlow<>(43);

    Flow<Integer> resFlow1 = InstrumentationGateway.mergeFlows(flow1, flow2);
    Flow<Integer> resFlow2 = InstrumentationGateway.mergeFlows(flow2, flow1);

    assertEquals(43, resFlow1.getResult());
    assertEquals(42, resFlow2.getResult());
  }

  private static class Callback<D, T>
      implements Supplier<Flow<D>>,
          BiConsumer<RequestContext, T>,
          TriConsumer<RequestContext, T, T>,
          BiFunction<RequestContext, T, Flow<Void>>,
          TriFunction<RequestContext, T, T, Flow<Void>> {

    private final RequestContext ctx;
    private final Flow<Void> flow;
    private int count = 0;
    private final Function<RequestContext, Flow<Void>> function;

    public Callback(RequestContext ctx, Flow<Void> flow) {
      this.ctx = ctx;
      this.flow = flow;
      function =
          input -> {
            count++;
            return flow;
          };
    }

    @Override
    public Flow<Void> apply(RequestContext requestContext, T arg) {
      count++;
      return flow;
    }

    @Override
    public Flow<D> get() {
      count++;
      return new Flow.ResultFlow<>((D) ctx);
    }

    @Override
    public void accept(RequestContext requestContext, T s, T s2) {
      count++;
    }

    public TriFunction<RequestContext, String, Short, Flow<Void>> asClientSocketAddress() {
      return (requestContext, s, aShort) -> {
        count++;
        return flow;
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Void> asRequestBodyStart() {
      return (requestContext, storedBodySupplier) -> {
        count++;
        return null;
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> asRequestBodyDone() {
      return (requestContext, storedBodySupplier) -> {
        count++;
        return new Flow.ResultFlow<>(null);
      };
    }

    @Override
    public void accept(RequestContext requestContext, T t) {
      count++;
    }

    @Override
    public Flow<Void> apply(RequestContext requestContext, T t, T t2) {
      count++;
      return flow;
    }
  }

  private static class Throwback<D, T>
      implements Supplier<Flow<D>>,
          BiConsumer<RequestContext, T>,
          TriConsumer<RequestContext, T, T>,
          BiFunction<RequestContext, T, Flow<Void>>,
          TriFunction<RequestContext, T, T, Flow<Void>> {

    private int count = 0;

    private final Function<RequestContext, Flow<Void>> function =
        input -> {
          count++;
          throw new IllegalArgumentException();
        };

    @Override
    public Flow<Void> apply(RequestContext requestContext, T arg) {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public Flow<D> get() {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public void accept(RequestContext requestContext, T s, T s2) {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public void accept(RequestContext requestContext, T t) {
      count++;
      throw new IllegalArgumentException();
    }

    public TriFunction<RequestContext, String, Short, Flow<Void>> asClientSocketAddress() {
      return (requestContext, s, aShort) -> {
        count++;
        throw new IllegalArgumentException();
      };
    }

    public BiFunction<RequestContext, String, Flow<Void>> asInferredClientAddress() {
      return (requestContext, s) -> {
        count++;
        throw new IllegalArgumentException();
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Void> asRequestBodyStart() {
      return (requestContext, storedBodySupplier) -> {
        count++;
        throw new IllegalArgumentException();
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> asRequestBodyDone() {
      return (requestContext, storedBodySupplier) -> {
        count++;
        throw new IllegalArgumentException();
      };
    }

    @Override
    public Flow<Void> apply(RequestContext requestContext, T t, T t2) {
      count++;
      throw new IllegalArgumentException();
    }
  }
}
