package datadog.trace.api.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import datadog.trace.api.Function;
import datadog.trace.api.TraceSegment;
import datadog.trace.api.function.BiConsumer;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.http.StoredBodySupplier;
import org.assertj.core.api.ThrowableAssert;
import org.junit.Before;
import org.junit.Test;

public class InstrumentationGatewayTest {

  private InstrumentationGateway gateway;
  private RequestContext<Object> context;
  private Flow<Void> flow;
  private Callback callback;
  private Events<Object> events;

  @Before
  public void setUp() {
    gateway = new InstrumentationGateway();
    context =
        new RequestContext<Object>() {
          @Override
          public Object getData() {
            return this;
          }

          @Override
          public TraceSegment getTraceSegment() {
            return TraceSegment.NoOp.INSTANCE;
          }
        };
    flow = new Flow.ResultFlow<>(null);
    callback = new Callback(context, flow);
    events = Events.get();
  }

  @Test
  public void testGetCallback() {
    gateway.registerCallback(events.requestStarted(), callback);
    // check event without registered callback
    assertThat(gateway.getCallback(events.requestEnded())).isNull();
    // check event with registered callback
    Supplier<Flow<Object>> cback = gateway.getCallback(events.requestStarted());
    assertThat(cback).isEqualTo(callback);
    Flow<Object> flow = cback.get();
    assertThat(flow.getAction()).isEqualTo(Flow.Action.Noop.INSTANCE);
    Object ctxt = flow.getResult();
    assertThat(ctxt).isEqualTo(context);
  }

  @Test
  public void testRegisterCallback() {
    Subscription s1 = gateway.registerCallback(events.requestStarted(), callback);
    // check event without registered callback
    assertThat(gateway.getCallback(events.requestEnded())).isNull();
    // check event with registered callback
    assertThat(gateway.getCallback(events.requestStarted())).isEqualTo(callback);
    // check that we can register a callback
    Callback cb = new Callback(context, flow);
    Subscription s2 = gateway.registerCallback(events.requestEnded(), cb);
    assertThat(gateway.getCallback(events.requestEnded())).isEqualTo(cb);
    // check that we can cancel a callback
    s1.cancel();
    assertThat(gateway.getCallback(events.requestStarted())).isNull();
    // check that we didn't remove the other callback
    assertThat(gateway.getCallback(events.requestEnded())).isEqualTo(cb);
  }

  @Test
  public void testDoubleRegistration() {
    gateway.registerCallback(events.requestStarted(), callback);
    // check event with registered callback
    assertThat(gateway.getCallback(events.requestStarted())).isEqualTo(callback);
    // check that we can't overwrite the callback
    assertThatThrownBy(
            new ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() throws Throwable {
                gateway.registerCallback(events.requestStarted(), callback);
              }
            })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Trying to overwrite existing callback ")
        .hasMessageContaining(events.requestStarted().toString());
  }

  @Test
  public void testDoubleCancel() {
    Subscription s1 = gateway.registerCallback(events.requestStarted(), callback);
    // check event with registered callback
    assertThat(gateway.getCallback(events.requestStarted())).isEqualTo(callback);
    // check that we can cancel a callback
    s1.cancel();
    assertThat(gateway.getCallback(events.requestStarted())).isNull();
    // check that we can cancel a callback
    s1.cancel();
    assertThat(gateway.getCallback(events.requestStarted())).isNull();
  }

  @Test
  public void testNoopAction() {
    assertThat(Flow.Action.Noop.INSTANCE.isBlocking()).isFalse();
  }

  @Test
  public void testThrownAction() {
    Flow.Action.Throw thrown = new Flow.Action.Throw(new Exception("my message"));
    assertThat(thrown.isBlocking()).isTrue();
    assertThat(thrown.getBlockingException().getMessage()).isEqualTo("my message");
  }

  @Test
  public void testNormalCalls() {
    // check that we pass through normal calls
    gateway.registerCallback(events.requestStarted(), callback);
    assertThat(gateway.getCallback(events.requestStarted()).get().getResult()).isEqualTo(context);
    gateway.registerCallback(events.requestEnded(), callback);
    assertThat(gateway.getCallback(events.requestEnded()).apply(null, null)).isEqualTo(flow);
    gateway.registerCallback(events.requestHeader(), callback);
    gateway.getCallback(events.requestHeader()).accept(null, null, null);
    gateway.registerCallback(events.requestHeaderDone(), callback);
    assertThat(gateway.getCallback(events.requestHeaderDone()).apply(null)).isEqualTo(flow);
    gateway.registerCallback(events.requestMethodUriRaw(), callback);
    assertThat(gateway.getCallback(events.requestMethodUriRaw()).apply(null, null, null))
        .isEqualTo(flow);
    gateway.registerCallback(events.requestClientSocketAddress(), callback.asClientSocketAddress());
    assertThat(gateway.getCallback(events.requestClientSocketAddress()).apply(null, null, null))
        .isEqualTo(flow);
    gateway.registerCallback(events.requestBodyStart(), callback.asRequestBodyStart());
    assertThat(gateway.getCallback(events.requestBodyStart()).apply(null, null)).isNull();
    gateway.registerCallback(events.requestBodyDone(), callback.asRequestBodyDone());
    assertThat(gateway.getCallback(events.requestBodyDone()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    gateway.registerCallback(events.responseStarted(), callback);
    gateway.getCallback(events.responseStarted()).accept(null, null);
    assertThat(callback.count).isEqualTo(Events.MAX_EVENTS);
  }

  @Test
  public void testThrowableBlocking() {
    Throwback throwback = new Throwback();
    // check that we block the thrown exceptions
    gateway.registerCallback(events.requestStarted(), throwback);
    assertThat(gateway.getCallback(events.requestStarted()).get())
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(events.requestEnded(), throwback);
    assertThat(gateway.getCallback(events.requestEnded()).apply(null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(events.requestHeader(), throwback);
    gateway.getCallback(events.requestHeader()).accept(null, null, null);
    gateway.registerCallback(events.requestHeaderDone(), throwback);
    assertThat(gateway.getCallback(events.requestHeaderDone()).apply(null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(events.requestMethodUriRaw(), throwback);
    assertThat(gateway.getCallback(events.requestMethodUriRaw()).apply(null, null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(
        events.requestClientSocketAddress(), throwback.asClientSocketAddress());
    assertThat(gateway.getCallback(events.requestClientSocketAddress()).apply(null, null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(events.requestBodyStart(), throwback.asRequestBodyStart());
    assertThat(gateway.getCallback(events.requestBodyStart()).apply(null, null)).isNull();
    gateway.registerCallback(events.requestBodyDone(), throwback.asRequestBodyDone());
    assertThat(gateway.getCallback(events.requestBodyDone()).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    gateway.registerCallback(events.responseStarted(), throwback);
    gateway.getCallback(events.responseStarted()).accept(null, null);
    assertThat(throwback.count).isEqualTo(Events.MAX_EVENTS);
  }

  private static class Callback<D, T>
      implements Supplier<Flow<D>>,
          Function<RequestContext<D>, Flow<Void>>,
          BiConsumer<RequestContext<D>, T>,
          TriConsumer<RequestContext<D>, T, T>,
          BiFunction<RequestContext<D>, T, Flow<Void>>,
          TriFunction<RequestContext<D>, T, T, Flow<Void>> {

    private final RequestContext<D> ctxt;
    private final Flow<Void> flow;
    private int count = 0;

    public Callback(RequestContext<D> ctxt, Flow<Void> flow) {
      this.ctxt = ctxt;
      this.flow = flow;
    }

    @Override
    public Flow<Void> apply(RequestContext<D> input) {
      count++;
      return flow;
    }

    @Override
    public Flow<Void> apply(RequestContext<D> requestContext, T arg) {
      count++;
      return flow;
    }

    @Override
    public Flow<D> get() {
      count++;
      return new Flow.ResultFlow<>((D) ctxt);
    }

    @Override
    public void accept(RequestContext<D> requestContext, T s, T s2) {
      count++;
    }

    public TriFunction<RequestContext<D>, String, Short, Flow<Void>> asClientSocketAddress() {
      return new TriFunction<RequestContext<D>, String, Short, Flow<Void>>() {
        @Override
        public Flow<Void> apply(RequestContext<D> requestContext, String s, Short aShort) {
          count++;
          return flow;
        }
      };
    }

    public BiFunction<RequestContext<D>, StoredBodySupplier, Void> asRequestBodyStart() {
      return new BiFunction<RequestContext<D>, StoredBodySupplier, Void>() {
        @Override
        public Void apply(RequestContext<D> requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          return null;
        }
      };
    }

    public BiFunction<RequestContext<D>, StoredBodySupplier, Flow<Void>> asRequestBodyDone() {
      return new BiFunction<RequestContext<D>, StoredBodySupplier, Flow<Void>>() {
        @Override
        public Flow<Void> apply(
            RequestContext<D> requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          return new Flow.ResultFlow<>(null);
        }
      };
    }

    @Override
    public void accept(RequestContext<D> requestContext, T t) {
      count++;
    }

    @Override
    public Flow<Void> apply(RequestContext<D> requestContext, T t, T t2) {
      count++;
      return flow;
    }
  }

  private static class Throwback<D, T>
      implements Supplier<Flow<D>>,
          Function<RequestContext<D>, Flow<Void>>,
          BiConsumer<RequestContext<D>, T>,
          TriConsumer<RequestContext<D>, T, T>,
          BiFunction<RequestContext<D>, T, Flow<Void>>,
          TriFunction<RequestContext<D>, T, T, Flow<Void>> {

    private int count = 0;

    @Override
    public Flow<Void> apply(RequestContext<D> input) {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public Flow<Void> apply(RequestContext<D> requestContext, T arg) {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public Flow<D> get() {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public void accept(RequestContext<D> requestContext, T s, T s2) {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public void accept(RequestContext<D> requestContext, T t) {
      count++;
      throw new IllegalArgumentException();
    }

    public TriFunction<RequestContext<D>, String, Short, Flow<Void>> asClientSocketAddress() {
      return new TriFunction<RequestContext<D>, String, Short, Flow<Void>>() {
        @Override
        public Flow<Void> apply(RequestContext<D> requestContext, String s, Short aShort) {
          count++;
          throw new IllegalArgumentException();
        }
      };
    }

    public BiFunction<RequestContext<D>, StoredBodySupplier, Void> asRequestBodyStart() {
      return new BiFunction<RequestContext<D>, StoredBodySupplier, Void>() {
        @Override
        public Void apply(RequestContext<D> requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          throw new IllegalArgumentException();
        }
      };
    }

    public BiFunction<RequestContext<D>, StoredBodySupplier, Flow<Void>> asRequestBodyDone() {
      return new BiFunction<RequestContext<D>, StoredBodySupplier, Flow<Void>>() {
        @Override
        public Flow<Void> apply(
            RequestContext<D> requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          throw new IllegalArgumentException();
        }
      };
    }

    public int getCount() {
      return count;
    }

    @Override
    public Flow<Void> apply(RequestContext<D> requestContext, T t, T t2) {
      count++;
      throw new IllegalArgumentException();
    }
  }
}
