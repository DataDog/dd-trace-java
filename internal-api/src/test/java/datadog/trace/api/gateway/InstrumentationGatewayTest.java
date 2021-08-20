package datadog.trace.api.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import datadog.trace.api.Function;
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
  private RequestContext context;
  private Flow<Void> flow;
  private Callback callback;

  @Before
  public void setUp() {
    gateway = new InstrumentationGateway();
    context = new RequestContext() {};
    flow = new Flow.ResultFlow<>(null);
    callback = new Callback(context, flow);
  }

  @Test
  public void testGetCallback() {
    gateway.registerCallback(Events.REQUEST_STARTED, callback);
    // check event without registered callback
    assertThat(gateway.getCallback(Events.REQUEST_ENDED)).isNull();
    // check event with registered callback
    Supplier<Flow<RequestContext>> cback = gateway.getCallback(Events.REQUEST_STARTED);
    assertThat(cback).isEqualTo(callback);
    Flow<RequestContext> flow = cback.get();
    assertThat(flow.getAction()).isEqualTo(Flow.Action.Noop.INSTANCE);
    RequestContext ctxt = flow.getResult();
    assertThat(ctxt).isEqualTo(context);
  }

  @Test
  public void testRegisterCallback() {
    Subscription s1 = gateway.registerCallback(Events.REQUEST_STARTED, callback);
    // check event without registered callback
    assertThat(gateway.getCallback(Events.REQUEST_ENDED)).isNull();
    // check event with registered callback
    assertThat(gateway.getCallback(Events.REQUEST_STARTED)).isEqualTo(callback);
    // check that we can register a callback
    Callback cb = new Callback(context, flow);
    Subscription s2 = gateway.registerCallback(Events.REQUEST_ENDED, cb);
    assertThat(gateway.getCallback(Events.REQUEST_ENDED)).isEqualTo(cb);
    // check that we can cancel a callback
    s1.cancel();
    assertThat(gateway.getCallback(Events.REQUEST_STARTED)).isNull();
    // check that we didn't remove the other callback
    assertThat(gateway.getCallback(Events.REQUEST_ENDED)).isEqualTo(cb);
  }

  @Test
  public void testDoubleRegistration() {
    gateway.registerCallback(Events.REQUEST_STARTED, callback);
    // check event with registered callback
    assertThat(gateway.getCallback(Events.REQUEST_STARTED)).isEqualTo(callback);
    // check that we can't overwrite the callback
    assertThatThrownBy(
            new ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() throws Throwable {
                gateway.registerCallback(Events.REQUEST_STARTED, callback);
              }
            })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Trying to overwrite existing callback ")
        .hasMessageContaining(Events.REQUEST_STARTED.toString());
  }

  @Test
  public void testDoubleCancel() {
    Subscription s1 = gateway.registerCallback(Events.REQUEST_STARTED, callback);
    // check event with registered callback
    assertThat(gateway.getCallback(Events.REQUEST_STARTED)).isEqualTo(callback);
    // check that we can cancel a callback
    s1.cancel();
    assertThat(gateway.getCallback(Events.REQUEST_STARTED)).isNull();
    // check that we can cancel a callback
    s1.cancel();
    assertThat(gateway.getCallback(Events.REQUEST_STARTED)).isNull();
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
    gateway.registerCallback(Events.REQUEST_STARTED, callback);
    assertThat(gateway.getCallback(Events.REQUEST_STARTED).get().getResult()).isEqualTo(context);
    gateway.registerCallback(Events.REQUEST_ENDED, callback);
    assertThat(gateway.getCallback(Events.REQUEST_ENDED).apply(null, null)).isEqualTo(flow);
    gateway.registerCallback(Events.REQUEST_METHOD, callback);
    gateway.getCallback(Events.REQUEST_METHOD).accept(null, null);
    gateway.registerCallback(Events.REQUEST_HEADER, callback);
    gateway.getCallback(Events.REQUEST_HEADER).accept(null, null, null);
    gateway.registerCallback(Events.REQUEST_HEADER_DONE, callback);
    assertThat(gateway.getCallback(Events.REQUEST_HEADER_DONE).apply(null)).isEqualTo(flow);
    gateway.registerCallback(Events.REQUEST_URI_RAW, callback);
    assertThat(gateway.getCallback(Events.REQUEST_URI_RAW).apply(null, null)).isEqualTo(flow);
    gateway.registerCallback(
        Events.REQUEST_CLIENT_SOCKET_ADDRESS, callback.asClientSocketAddress());
    assertThat(gateway.getCallback(Events.REQUEST_CLIENT_SOCKET_ADDRESS).apply(null, null, null))
        .isEqualTo(flow);
    gateway.registerCallback(Events.REQUEST_BODY_START, callback.asRequestBodyStart());
    assertThat(gateway.getCallback(Events.REQUEST_BODY_START).apply(null, null)).isNull();
    gateway.registerCallback(Events.REQUEST_BODY_DONE, callback.asRequestBodyDone());
    assertThat(gateway.getCallback(Events.REQUEST_BODY_DONE).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    assertThat(callback.count).isEqualTo(Events.MAX_EVENTS);
  }

  @Test
  public void testThrowableBlocking() {
    Throwback throwback = new Throwback();
    // check that we block the thrown exceptions
    gateway.registerCallback(Events.REQUEST_STARTED, throwback);
    assertThat(gateway.getCallback(Events.REQUEST_STARTED).get())
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(Events.REQUEST_ENDED, throwback);
    assertThat(gateway.getCallback(Events.REQUEST_ENDED).apply(null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(Events.REQUEST_HEADER, throwback);
    gateway.getCallback(Events.REQUEST_HEADER).accept(null, null, null);
    gateway.registerCallback(Events.REQUEST_HEADER_DONE, throwback);
    assertThat(gateway.getCallback(Events.REQUEST_HEADER_DONE).apply(null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(Events.REQUEST_METHOD, throwback);
    gateway.getCallback(Events.REQUEST_METHOD).accept(null, null);
    gateway.registerCallback(Events.REQUEST_URI_RAW, throwback);
    assertThat(gateway.getCallback(Events.REQUEST_URI_RAW).apply(null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(
        Events.REQUEST_CLIENT_SOCKET_ADDRESS, throwback.asClientSocketAddress());
    assertThat(gateway.getCallback(Events.REQUEST_CLIENT_SOCKET_ADDRESS).apply(null, null, null))
        .isEqualTo(Flow.ResultFlow.empty());
    gateway.registerCallback(Events.REQUEST_BODY_START, throwback.asRequestBodyStart());
    assertThat(gateway.getCallback(Events.REQUEST_BODY_START).apply(null, null)).isNull();
    gateway.registerCallback(Events.REQUEST_BODY_DONE, throwback.asRequestBodyDone());
    assertThat(gateway.getCallback(Events.REQUEST_BODY_DONE).apply(null, null).getAction())
        .isEqualTo(Flow.Action.Noop.INSTANCE);
    assertThat(throwback.count).isEqualTo(Events.MAX_EVENTS);
  }

  private static class Callback<T>
      implements Supplier<Flow<RequestContext>>,
          Function<RequestContext, Flow<Void>>,
          BiConsumer<RequestContext, T>,
          TriConsumer<RequestContext, T, T>,
          BiFunction<RequestContext, T, Flow<Void>> {

    private final RequestContext ctxt;
    private final Flow<Void> flow;
    private int count = 0;

    public Callback(RequestContext ctxt, Flow<Void> flow) {
      this.ctxt = ctxt;
      this.flow = flow;
    }

    @Override
    public Flow<Void> apply(RequestContext input) {
      count++;
      return flow;
    }

    @Override
    public Flow<Void> apply(RequestContext requestContext, T arg) {
      count++;
      return flow;
    }

    @Override
    public Flow<RequestContext> get() {
      count++;
      return new Flow.ResultFlow<>(ctxt);
    }

    @Override
    public void accept(RequestContext requestContext, T s, T s2) {
      count++;
    }

    public TriFunction<RequestContext, String, Short, Flow<Void>> asClientSocketAddress() {
      return new TriFunction<RequestContext, String, Short, Flow<Void>>() {
        @Override
        public Flow<Void> apply(RequestContext requestContext, String s, Short aShort) {
          count++;
          return flow;
        }
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Void> asRequestBodyStart() {
      return new BiFunction<RequestContext, StoredBodySupplier, Void>() {
        @Override
        public Void apply(RequestContext requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          return null;
        }
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> asRequestBodyDone() {
      return new BiFunction<RequestContext, StoredBodySupplier, Flow<Void>>() {
        @Override
        public Flow<Void> apply(
            RequestContext requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          return new Flow.ResultFlow<>(null);
        }
      };
    }

    @Override
    public void accept(RequestContext requestContext, T t) {
      count++;
    }
  }

  private static class Throwback<T>
      implements Supplier<Flow<RequestContext>>,
          Function<RequestContext, Flow<Void>>,
          BiConsumer<RequestContext, T>,
          TriConsumer<RequestContext, T, T>,
          BiFunction<RequestContext, T, Flow<Void>> {

    private int count = 0;

    @Override
    public Flow<Void> apply(RequestContext input) {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public Flow<Void> apply(RequestContext requestContext, T arg) {
      count++;
      throw new IllegalArgumentException();
    }

    @Override
    public Flow<RequestContext> get() {
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
      return new TriFunction<RequestContext, String, Short, Flow<Void>>() {
        @Override
        public Flow<Void> apply(RequestContext requestContext, String s, Short aShort) {
          count++;
          throw new IllegalArgumentException();
        }
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Void> asRequestBodyStart() {
      return new BiFunction<RequestContext, StoredBodySupplier, Void>() {
        @Override
        public Void apply(RequestContext requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          throw new IllegalArgumentException();
        }
      };
    }

    public BiFunction<RequestContext, StoredBodySupplier, Flow<Void>> asRequestBodyDone() {
      return new BiFunction<RequestContext, StoredBodySupplier, Flow<Void>>() {
        @Override
        public Flow<Void> apply(
            RequestContext requestContext, StoredBodySupplier storedBodySupplier) {
          count++;
          throw new IllegalArgumentException();
        }
      };
    }

    public int getCount() {
      return count;
    }
  }
}
