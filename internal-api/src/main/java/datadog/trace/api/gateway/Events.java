package datadog.trace.api.gateway;

import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

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
  public EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>> requestEnded() {
    return (EventType<BiFunction<RequestContext, IGSpanInfo, Flow<Void>>>) REQUEST_ENDED;
  }

  static final int REQUEST_HEADER_ID = 2;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_HEADER =
      new ET<>("server.request.header", REQUEST_HEADER_ID);
  /** A request header as a key and values separated by , */
  @SuppressWarnings("unchecked")
  public EventType<TriConsumer<RequestContext, String, String>> requestHeader() {
    return (EventType<TriConsumer<RequestContext, String, String>>) REQUEST_HEADER;
  }

  static final int REQUEST_HEADER_DONE_ID = 3;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_HEADER_DONE =
      new ET<>("server.request.header.done", REQUEST_HEADER_DONE_ID);
  /** All request headers have been provided */
  @SuppressWarnings("unchecked")
  public EventType<Function<RequestContext, Flow<Void>>> requestHeaderDone() {
    return (EventType<Function<RequestContext, Flow<Void>>>) REQUEST_HEADER_DONE;
  }

  static final int REQUEST_METHOD_URI_RAW_ID = 4;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_METHOD_URI_RAW =
      new ET<>("server.request.method.uri.raw", REQUEST_METHOD_URI_RAW_ID);
  /** The method (uppercase) and URIDataAdapter for the request. */
  @SuppressWarnings("unchecked")
  public EventType<TriFunction<RequestContext, String /* method */, URIDataAdapter, Flow<Void>>>
      requestMethodUriRaw() {
    return (EventType<TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>>)
        REQUEST_METHOD_URI_RAW;
  }

  static final int REQUEST_PATH_PARAMS_ID = 5;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_PATH_PARAMS =
      new ET<>("server.request.method.uri.raw", REQUEST_PATH_PARAMS_ID);
  /** The parameters the framework got from the request uri (but not the query string) */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, Map<String, ?>, Flow<Void>>> requestPathParams() {
    return (EventType<BiFunction<RequestContext, Map<String, ?>, Flow<Void>>>) REQUEST_PATH_PARAMS;
  }

  static final int REQUEST_CLIENT_SOCKET_ADDRESS_ID = 6;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_CLIENT_SOCKET_ADDRESS =
      new ET<>("http.server.client_socket_address", REQUEST_CLIENT_SOCKET_ADDRESS_ID);
  /** The method (uppercase) and URIDataAdapter for the request. */
  @SuppressWarnings("unchecked")
  public EventType<TriFunction<RequestContext, String, Integer, Flow<Void>>>
      requestClientSocketAddress() {
    return (EventType<TriFunction<RequestContext, String, Integer, Flow<Void>>>)
        REQUEST_CLIENT_SOCKET_ADDRESS;
  }

  static final int REQUEST_INFERRED_CLIENT_ADDRESS_ID = 7;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_INFERRED_CLIENT_ADDRESS =
      new ET<>("http.server.inferred_client_address", REQUEST_INFERRED_CLIENT_ADDRESS_ID);
  /** The inferred client IP address. */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String, Flow<Void>>> requestInferredClientAddress() {
    return (EventType<BiFunction<RequestContext, String, Flow<Void>>>)
        REQUEST_INFERRED_CLIENT_ADDRESS;
  }

  static final int REQUEST_BODY_START_ID = 8;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_BODY_START =
      new ET<>("request.body.started", REQUEST_BODY_START_ID);
  /** The request body has started being read */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, StoredBodySupplier, Void>> requestBodyStart() {
    return (EventType<BiFunction<RequestContext, StoredBodySupplier, Void>>) REQUEST_BODY_START;
  }

  static final int REQUEST_BODY_DONE_ID = 9;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_BODY_DONE =
      new ET<>("request.body.done", REQUEST_BODY_DONE_ID);
  /** The request body is done being read */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, StoredBodySupplier, Flow<Void>>> requestBodyDone() {
    return (EventType<BiFunction<RequestContext, StoredBodySupplier, Flow<Void>>>)
        REQUEST_BODY_DONE;
  }

  static final int REQUEST_BODY_CONVERTED_ID = 10;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_BODY_CONVERTED =
      new ET<>("request.body.converted", REQUEST_BODY_CONVERTED_ID);
  /** The request body has been converted by the framework */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, Object, Flow<Void>>> requestBodyProcessed() {
    return (EventType<BiFunction<RequestContext, Object, Flow<Void>>>) REQUEST_BODY_CONVERTED;
  }

  static final int RESPONSE_STARTED_ID = 11;

  @SuppressWarnings("rawtypes")
  private static final EventType RESPONSE_STARTED =
      new ET<>("response.started", RESPONSE_STARTED_ID);
  /** A response started */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, Integer, Flow<Void>>> responseStarted() {
    return (EventType<BiFunction<RequestContext, Integer, Flow<Void>>>) RESPONSE_STARTED;
  }

  static final int RESPONSE_HEADER_ID = 12;

  @SuppressWarnings("rawtypes")
  private static final EventType RESPONSE_HEADER =
      new ET<>("server.response.header", RESPONSE_HEADER_ID);
  /** A response header as a key and values separated by , */
  @SuppressWarnings("unchecked")
  public EventType<TriConsumer<RequestContext, String, String>> responseHeader() {
    return (EventType<TriConsumer<RequestContext, String, String>>) RESPONSE_HEADER;
  }

  static final int RESPONSE_HEADER_DONE_ID = 13;

  @SuppressWarnings("rawtypes")
  private static final EventType RESPONSE_HEADER_DONE =
      new ET<>("server.response.header.done", RESPONSE_HEADER_DONE_ID);
  /** All response headers have been provided */
  @SuppressWarnings("unchecked")
  public EventType<Function<RequestContext, Flow<Void>>> responseHeaderDone() {
    return (EventType<Function<RequestContext, Flow<Void>>>) RESPONSE_HEADER_DONE;
  }

  static final int GRPC_SERVER_REQUEST_MESSAGE_ID = 14;

  @SuppressWarnings("rawtypes")
  private static final EventType GRPC_SERVER_REQUEST_MESSAGE =
      new ET<>("grpc.server.request.message", GRPC_SERVER_REQUEST_MESSAGE_ID);
  /** All response headers have been provided */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, Object, Flow<Void>>> grpcServerRequestMessage() {
    return (EventType<BiFunction<RequestContext, Object, Flow<Void>>>) GRPC_SERVER_REQUEST_MESSAGE;
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
