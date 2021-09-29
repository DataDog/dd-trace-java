package datadog.trace.api.gateway;

import datadog.trace.api.Function;
import datadog.trace.api.function.*;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.util.concurrent.atomic.AtomicInteger;

/** All known {@code EventType} that the {@code InstrumentationGateway} can handle. */
public final class Events<D> {
  private static final AtomicInteger nextId = new AtomicInteger(0);

  // The IDs that we define here need to be compile time constants for Java
  // to be able to do a switch statement, hence we can't use nextId in the
  // definitions of them :(
  // There is a double check in the ET constructor that makes sure that we
  // assign the IDs correctly.

  static final int REQUEST_STARTED_ID = 0;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_STARTED = new ET<>("request.started", REQUEST_STARTED_ID);
  /** A request started */
  @SuppressWarnings("unchecked")
  public EventType<Supplier<Flow<D>>> requestStarted() {
    return (EventType<Supplier<Flow<D>>>) REQUEST_STARTED;
  }

  static final int REQUEST_ENDED_ID = 1;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_ENDED = new ET<>("request.ended", REQUEST_ENDED_ID);
  /** A request ended */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext<D>, IGSpanInfo, Flow<Void>>> requestEnded() {
    return (EventType<BiFunction<RequestContext<D>, IGSpanInfo, Flow<Void>>>) REQUEST_ENDED;
  }

  static final int REQUEST_HEADER_ID = 2;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_HEADER =
      new ET<>("server.request.header", REQUEST_HEADER_ID);
  /** A request header as a key and values separated by , */
  @SuppressWarnings("unchecked")
  public EventType<TriConsumer<RequestContext<D>, String, String>> requestHeader() {
    return (EventType<TriConsumer<RequestContext<D>, String, String>>) REQUEST_HEADER;
  }

  static final int REQUEST_HEADER_DONE_ID = 3;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_HEADER_DONE =
      new ET<>("server.request.header.done", REQUEST_HEADER_DONE_ID);
  /** All request headers have been provided */
  @SuppressWarnings("unchecked")
  public EventType<Function<RequestContext<D>, Flow<Void>>> requestHeaderDone() {
    return (EventType<Function<RequestContext<D>, Flow<Void>>>) REQUEST_HEADER_DONE;
  }

  static final int REQUEST_METHOD_URI_RAW_ID = 4;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_METHOD_URI_RAW =
      new ET<>("server.request.method.uri.raw", REQUEST_METHOD_URI_RAW_ID);
  /** The method (uppercase) and URIDataAdapter for the request. */
  @SuppressWarnings("unchecked")
  public EventType<TriFunction<RequestContext<D>, String /* method */, URIDataAdapter, Flow<Void>>>
      requestMethodUriRaw() {
    return (EventType<TriFunction<RequestContext<D>, String, URIDataAdapter, Flow<Void>>>)
        REQUEST_METHOD_URI_RAW;
  }

  static final int REQUEST_CLIENT_SOCKET_ADDRESS_ID = 5;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_CLIENT_SOCKET_ADDRESS =
      new ET<>("http.server.client_socket_address", REQUEST_CLIENT_SOCKET_ADDRESS_ID);
  /** The method (uppercase) and URIDataAdapter for the request. */
  @SuppressWarnings("unchecked")
  public EventType<TriFunction<RequestContext<D>, String, Integer, Flow<Void>>>
      requestClientSocketAddress() {
    return (EventType<TriFunction<RequestContext<D>, String, Integer, Flow<Void>>>)
        REQUEST_CLIENT_SOCKET_ADDRESS;
  }

  static final int REQUEST_BODY_START_ID = 6;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_BODY_START =
      new ET<>("request.body.started", REQUEST_BODY_START_ID);
  /** The request body has started being read */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext<D>, StoredBodySupplier, Void>> requestBodyStart() {
    return (EventType<BiFunction<RequestContext<D>, StoredBodySupplier, Void>>) REQUEST_BODY_START;
  }

  static final int REQUEST_BODY_DONE_ID = 7;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_BODY_DONE =
      new ET<>("request.body.done", REQUEST_BODY_DONE_ID);
  /** The request body is done being read */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext<D>, StoredBodySupplier, Flow<Void>>>
      requestBodyDone() {
    return (EventType<BiFunction<RequestContext<D>, StoredBodySupplier, Flow<Void>>>)
        REQUEST_BODY_DONE;
  }

  static final int MAX_EVENTS = nextId.get();

  private static final class ET<T> extends EventType<T> {
    public ET(String type, int id) {
      super(type, id);
      int expectedId = nextId.getAndIncrement();
      if (id != expectedId) {
        throw new IllegalArgumentException(
            "Event " + type + " has broken id " + id + ", expected " + expectedId + ".");
      }
    }
  }

  private Events() {}

  public static final Events<Object> EVENTS = new Events<>();

  @SuppressWarnings("unchecked")
  public static <D> Events<D> get() {
    return (Events<D>) EVENTS;
  }
}
