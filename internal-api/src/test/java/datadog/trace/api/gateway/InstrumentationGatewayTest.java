package datadog.trace.api.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.IOException;
import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert;
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
          public void close() throws IOException {}

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
    assertThat(cbp.getCallback(events.requestEnded())).isNull();
    // check event with registered callback
    Supplier<Flow<Object>> cback = cbp.getCallback(events.requestStarted());
    assertThat(cback).isEqualTo(callback);
    Flow<Object> flow = cback.get();
    assertThat(flow.getAction()).isEqualTo(Flow.Action.Noop.INSTANCE);
    Object ctxt = flow.getResult();
    assertThat(ctxt).isEqualTo(context);
  }

  @Test
  public void testRegisterCallback() {
    Subscription s1 = ss.registerCallback(events.requestStarted(), callback);
    // check event without registered callback
    assertThat(cbp.getCallback(events.requestEnded())).isNull();
    // check event with registered callback
    assertThat(cbp.getCallback(events.requestStarted())).isEqualTo(callback);
    // check that we can register a callback
    Callback cb = new Callback(context, flow);
    Subscription s2 = ss.registerCallback(events.requestEnded(), cb);
    assertThat(cbp.getCallback(events.requestEnded())).isEqualTo(cb);
    // check that we can cancel a callback
    s1.cancel();
    assertThat(cbp.getCallback(events.requestStarted())).isNull();
    // check that we didn't remove the other callback
    assertThat(cbp.getCallback(events.requestEnded())).isEqualTo(cb);
  }

  @Test
  public void testDoubleRegistration() {
    ss.registerCallback(events.requestStarted(), callback);
    // check event with registered callback
    assertThat(cbp.getCallback(events.requestStarted())).isEqualTo(callback);
    // check that we can't overwrite the callback
    assertThatThrownBy(
            new ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() throws Throwable {
                ss.registerCallback(events.requestStarted(), callback);
              }
            })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Trying to overwrite existing callback ")
        .hasMessageContaining(events.requestStarted().toString());
  }

  @Test
  public void testDoubleCancel() {
    Subscription s1 = ss.registerCallback(events.requestStarted(), callback);
    // check event with registered callback
    assertThat(cbp.getCallback(events.requestStarted())).isEqualTo(callback);
    // check that we can cancel a callback
    s1.cancel();
    assertThat(cbp.getCallback(events.requestStarted())).isNull();
    // check that we can cancel a callback
    s1.cancel();
    assertThat(cbp.getCallback(events.requestStarted())).isNull();
  }

  @Test
  public void testNoopAction() {
    assertThat(Flow.Action.Noop.INSTANCE.isBlocking()).isFalse();
  }

  @Test
  public void testRequestBlockingAction() {
    Flow.Action.RequestBlockingAction rba =
        new Flow.Action.RequestBlockingAction(400, BlockingContentType.HTML);
    assertThat(rba.isBlocking()).isTrue();
    assertThat(rba.getStatusCode()).isEqualTo(400);
    assertThat(rba.getBlockingContentType()).isEqualTo(BlockingContentType.HTML);

    rba =
        new Flow.Action.RequestBlockingAction(
            400,
            BlockingContentType.HTML,
            Collections.singletonMap("Location", "https://www.google.com/"));
    assertThat(rba.isBlocking()).isTrue();
    assertThat(rba.getStatusCode()).isEqualTo(400);
    assertThat(rba.getBlockingContentType()).isEqualTo(BlockingContentType.HTML);
    assertThat(rba.getExtraHeaders().get("Location")).isEqualTo("https://www.google.com/");

    rba = Flow.Action.RequestBlockingAction.forRedirect(301, "https://www.google.com/");
    assertThat(rba.isBlocking()).isTrue();
    assertThat(rba.getStatusCode()).isEqualTo(301);
    assertThat(rba.getBlockingContentType()).isEqualTo(BlockingContentType.NONE);
    assertThat(rba.getExtraHeaders().get("Location")).isEqualTo("https://www.google.com/");
  }

  @Test
  public void testNormalCalls() {
    // check that we pass through normal calls
    ss.registerCallback(events.requestStarted(), callback);
    assertThat(cbp.getCallback(events.requestStarted()).get().getResult()).isEqualTo(context);
    ss.registerCallback(events.requestEnded(), callback);
    assertThat(cbp.getCallback(events.requestEnded()).apply(null, null)).isEqualTo(flow);
    ss.registerCallback(events.requestHeader(), callback);
    cbp.getCallback(events.requestHeader()).accept(null, null, null);
    ss.registerCallback(events.requestHeaderDone(), callback.function);
    assertThat(cbp.getCallback(events.requestHeaderDone()).apply(null)).isEqualTo(flow);
    ss.registerCallback(events.requestMethodUriRaw(), callback);
    assertThat(cbp.getCallback(events.requestMethodUriRaw()).apply(null, null, null))
        .isEqualTo(flow);
    ss.registerCallback(events.requestPathParams(), callback);
    assertThat(cbp.getCallback(events.requestPathParams()).apply(null, null)).isEqualTo(flow);
    ss.registerCallback(events.requestClientSocketAddress(), callback.asClientSocketAddress());
    assertThat(cbp.getCallback(events.requestClientSocketAddress()).apply(null, null, null))
        .isEqualTo(flow);
    ss.registerCallback(events.requestInferredClientAddress(), callback);
    assertThat(cbp.getCallback(events.requestInferredClientAddress()).apply(null, null))
        .isEqualTo(flow);
    ss.registerCallback(events.requestBodyStart(), callback.asRequestBodyStart());
    assertThat(cbp.getCallback(events.requestBodyStart()).apply(null, null)).isNull();
    ss.registerCallback(events.requestBodyDone(), callback.asRequestBodyDone());
    assertThat(cbp.getCallback(events.requestBodyDone()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.requestBodyProcessed(), callback);
    assertThat(cbp.getCallback(events.requestBodyProcessed()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.responseBody(), callback);
    assertThat(cbp.getCallback(events.responseBody()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.grpcServerMethod(), callback);
    assertThat(cbp.getCallback(events.grpcServerMethod()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.grpcServerRequestMessage(), callback);
    assertThat(cbp.getCallback(events.grpcServerRequestMessage()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.responseStarted(), callback);
    cbp.getCallback(events.responseStarted()).apply(null, null);
    ss.registerCallback(events.responseHeader(), callback);
    cbp.getCallback(events.responseHeader()).accept(null, null, null);
    ss.registerCallback(events.responseHeaderDone(), callback.function);
    cbp.getCallback(events.responseHeaderDone()).apply(null);
    ss.registerCallback(events.graphqlServerRequestMessage(), callback);
    assertThat(cbp.getCallback(events.graphqlServerRequestMessage()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.databaseConnection(), callback);
    cbp.getCallback(events.databaseConnection()).accept(null, null);
    ss.registerCallback(events.databaseSqlQuery(), callback);
    cbp.getCallback(events.databaseSqlQuery()).apply(null, null);
    ss.registerCallback(events.networkConnection(), callback);
    cbp.getCallback(events.networkConnection()).apply(null, null);
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
    assertThat(callback.count).isEqualTo(Events.MAX_EVENTS);
  }

  @Test
  public void testThrowableBlocking() {
    Throwback throwback = new Throwback();
    // check that we block the thrown exceptions
    ss.registerCallback(events.requestStarted(), throwback);
    assertThat(cbp.getCallback(events.requestStarted()).get()).isEqualTo(Flow.ResultFlow.empty());
    ss.registerCallback(events.requestEnded(), throwback);
    assertThat(cbp.getCallback(events.requestEnded()).apply(null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    ss.registerCallback(events.requestHeader(), throwback);
    cbp.getCallback(events.requestHeader()).accept(null, null, null);
    ss.registerCallback(events.requestHeaderDone(), throwback.function);
    assertThat(cbp.getCallback(events.requestHeaderDone()).apply(null))
        .isEqualTo(Flow.ResultFlow.empty());
    ss.registerCallback(events.requestMethodUriRaw(), throwback);
    assertThat(cbp.getCallback(events.requestMethodUriRaw()).apply(null, null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    ss.registerCallback(events.requestPathParams(), throwback);
    assertThat(cbp.getCallback(events.requestPathParams()).apply(null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    ss.registerCallback(events.requestClientSocketAddress(), throwback.asClientSocketAddress());
    assertThat(cbp.getCallback(events.requestClientSocketAddress()).apply(null, null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    ss.registerCallback(events.requestInferredClientAddress(), throwback.asInferredClientAddress());
    assertThat(cbp.getCallback(events.requestInferredClientAddress()).apply(null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    ss.registerCallback(events.requestBodyStart(), throwback.asRequestBodyStart());
    assertThat(cbp.getCallback(events.requestBodyStart()).apply(null, null)).isNull();
    ss.registerCallback(events.requestBodyDone(), throwback.asRequestBodyDone());
    assertThat(cbp.getCallback(events.requestBodyDone()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.requestBodyProcessed(), throwback);
    assertThat(cbp.getCallback(events.requestBodyProcessed()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.responseBody(), throwback);
    assertThat(cbp.getCallback(events.responseBody()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.grpcServerMethod(), throwback);
    assertThat(cbp.getCallback(events.grpcServerMethod()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.grpcServerRequestMessage(), throwback);
    assertThat(cbp.getCallback(events.grpcServerRequestMessage()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.responseStarted(), throwback);
    cbp.getCallback(events.responseStarted()).apply(null, null);
    ss.registerCallback(events.responseHeader(), throwback);
    cbp.getCallback(events.responseHeader()).accept(null, null, null);
    ss.registerCallback(events.responseHeaderDone(), throwback.function);
    cbp.getCallback(events.responseHeaderDone()).apply(null);
    ss.registerCallback(events.graphqlServerRequestMessage(), throwback);
    assertThat(cbp.getCallback(events.graphqlServerRequestMessage()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    ss.registerCallback(events.databaseConnection(), throwback);
    cbp.getCallback(events.databaseConnection()).accept(null, null);
    ss.registerCallback(events.databaseSqlQuery(), throwback);
    cbp.getCallback(events.databaseSqlQuery()).apply(null, null);
    ss.registerCallback(events.networkConnection(), throwback);
    cbp.getCallback(events.networkConnection()).apply(null, null);
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
    assertThat(throwback.count).isEqualTo(Events.MAX_EVENTS);
  }

  @Test
  public void iastRegistryOperatesIndependently() {
    SubscriptionService ssIast = gateway.getSubscriptionService(RequestContextSlot.IAST);
    CallbackProvider cbpIast = gateway.getCallbackProvider(RequestContextSlot.IAST);

    ss.registerCallback(events.requestStarted(), callback);
    assertThat(cbpIast.getCallback(events.requestStarted())).isNull();

    ssIast.registerCallback(events.requestStarted(), callback);
    assertThat(cbpIast.getCallback(events.requestStarted())).isNotNull();
  }

  @Test
  public void resettingResetsAllSubsystems() {
    SubscriptionService ssIast = gateway.getSubscriptionService(RequestContextSlot.IAST);
    CallbackProvider cbpIast = gateway.getCallbackProvider(RequestContextSlot.IAST);

    ss.registerCallback(events.requestStarted(), callback);
    ssIast.registerCallback(events.requestStarted(), callback);

    gateway.reset();

    assertThat(cbp.getCallback(events.requestStarted())).isNull();
    assertThat(cbpIast.getCallback(events.requestStarted())).isNull();
  }

  @Test
  public void invalidRequestContextSlot() {
    SubscriptionService ss = gateway.getSubscriptionService(null);
    CallbackProvider cbp = gateway.getCallbackProvider(null);

    assertThat(ss).isSameAs(SubscriptionService.SubscriptionServiceNoop.INSTANCE);
    assertThat(cbp).isSameAs(CallbackProvider.CallbackProviderNoop.INSTANCE);
  }

  @Test
  public void universalCallbackProviderForRequestEnded() {
    SubscriptionService ssIast = gateway.getSubscriptionService(RequestContextSlot.IAST);
    final int[] count = new int[1];
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> cb =
        (requestContext, igSpanInfo) -> {
          assertThat(requestContext).isSameAs(callback.ctxt);
          assertThat(igSpanInfo).isSameAs(AgentTracer.noopSpan());
          count[0]++;
          return new Flow.ResultFlow<>(null);
        };
    ss.registerCallback(events.requestEnded(), cb);
    ssIast.registerCallback(events.requestEnded(), cb);
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> uniCb =
        gateway.getUniversalCallbackProvider().getCallback(events.requestEnded());
    Flow<Void> res = uniCb.apply(callback.ctxt, AgentTracer.noopSpan());

    assertThat(count[0]).isEqualTo(2);
    assertThat(res).isNotNull();

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> uniCb2 =
        gateway.getUniversalCallbackProvider().getCallback(events.requestEnded());
    assertThat(uniCb).isSameAs(uniCb2);
  }

  @Test
  public void universalCallbackWithOnlyAppSec() {
    ss.registerCallback(events.requestEnded(), callback);

    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> uniCb =
        gateway.getUniversalCallbackProvider().getCallback(events.requestEnded());
    BiFunction<RequestContext, IGSpanInfo, Flow<Void>> appSecCb =
        cbp.getCallback(events.requestEnded());
    assertThat(appSecCb).isSameAs(uniCb);
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
    assertThat(iastCb).isSameAs(uniCb);
  }

  @Test
  public void universalCallbackWithNoCallbacks() {
    assertThat(gateway.getUniversalCallbackProvider().getCallback(events.requestEnded())).isNull();
  }

  @Test
  public void mergeFlowIdenticalFlows() {
    Flow<Void> flow = new Flow.ResultFlow<>(null);
    Flow<Void> resFlow = InstrumentationGateway.mergeFlows(flow, flow);

    assertThat(resFlow).isSameAs(flow);
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

    assertThat(resFlow1.getAction().isBlocking()).isTrue();
    assertThat(resFlow2.getAction().isBlocking()).isTrue();
  }

  @Test
  public void mergeFlowReturnsNonNullResult() {
    Flow<Integer> flow1 = new Flow.ResultFlow<>(42);
    Flow<Integer> flow2 = new Flow.ResultFlow<>(null);

    Flow<Integer> resFlow1 = InstrumentationGateway.mergeFlows(flow1, flow2);
    Flow<Integer> resFlow2 = InstrumentationGateway.mergeFlows(flow2, flow1);

    assertThat(resFlow1.getResult()).isEqualTo(42);
    assertThat(resFlow2.getResult()).isEqualTo(42);
  }

  @Test
  public void mergeFlowReturnsLatestNonNullResult() {
    Flow<Integer> flow1 = new Flow.ResultFlow<>(42);
    Flow<Integer> flow2 = new Flow.ResultFlow<>(43);

    Flow<Integer> resFlow1 = InstrumentationGateway.mergeFlows(flow1, flow2);
    Flow<Integer> resFlow2 = InstrumentationGateway.mergeFlows(flow2, flow1);

    assertThat(resFlow1.getResult()).isEqualTo(43);
    assertThat(resFlow2.getResult()).isEqualTo(42);
  }

  private static class Callback<D, T>
      implements Supplier<Flow<D>>,
          BiConsumer<RequestContext, T>,
          TriConsumer<RequestContext, T, T>,
          BiFunction<RequestContext, T, Flow<Void>>,
          TriFunction<RequestContext, T, T, Flow<Void>> {

    private final RequestContext ctxt;
    private final Flow<Void> flow;
    private int count = 0;
    private final Function<RequestContext, Flow<Void>> function;

    public Callback(RequestContext ctxt, Flow<Void> flow) {
      this.ctxt = ctxt;
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
      return new Flow.ResultFlow<>((D) ctxt);
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

    public int getCount() {
      return count;
    }

    @Override
    public Flow<Void> apply(RequestContext requestContext, T t, T t2) {
      count++;
      throw new IllegalArgumentException();
    }
  }
}
