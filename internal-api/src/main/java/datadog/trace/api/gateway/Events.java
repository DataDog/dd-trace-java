package datadog.trace.api.gateway;

import datadog.trace.api.appsec.HttpClientRequest;
import datadog.trace.api.appsec.HttpClientResponse;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.http.StoredBodySupplier;
import datadog.trace.api.telemetry.LoginEvent;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
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

  static final int GRAPHQL_SERVER_REQUEST_MESSAGE_ID = 15;

  @SuppressWarnings("rawtypes")
  private static final EventType GRAPHQL_SERVER_REQUEST_MESSAGE =
      new ET<>("graphql.server.request.message", GRAPHQL_SERVER_REQUEST_MESSAGE_ID);

  /** Before resolver execution */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, Map<String, ?>, Flow<Void>>>
      graphqlServerRequestMessage() {
    return (EventType<BiFunction<RequestContext, Map<String, ?>, Flow<Void>>>)
        GRAPHQL_SERVER_REQUEST_MESSAGE;
  }

  static final int DATABASE_CONNECTION_ID = 16;

  @SuppressWarnings("rawtypes")
  private static final EventType DATABASE_CONNECTION =
      new ET<>("database.connection", DATABASE_CONNECTION_ID);

  /** A database connection */
  @SuppressWarnings("unchecked")
  public EventType<BiConsumer<RequestContext, String>> databaseConnection() {
    return (EventType<BiConsumer<RequestContext, String>>) DATABASE_CONNECTION;
  }

  static final int DATABASE_SQL_QUERY_ID = 17;

  @SuppressWarnings("rawtypes")
  private static final EventType DATABASE_SQL_QUERY =
      new ET<>("database.query", DATABASE_SQL_QUERY_ID);

  /** A database sql query */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String, Flow<Void>>> databaseSqlQuery() {
    return (EventType<BiFunction<RequestContext, String, Flow<Void>>>) DATABASE_SQL_QUERY;
  }

  static final int GRPC_SERVER_METHOD_ID = 18;

  @SuppressWarnings("rawtypes")
  private static final EventType GRPC_SERVER_METHOD =
      new ET<>("grpc.server.method", GRPC_SERVER_METHOD_ID);

  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String, Flow<Void>>> grpcServerMethod() {
    return (EventType<BiFunction<RequestContext, String, Flow<Void>>>) GRPC_SERVER_METHOD;
  }

  static final int HTTP_CLIENT_REQUEST_ID = 19;

  @SuppressWarnings("rawtypes")
  private static final EventType HTTP_CLIENT_REQUEST =
      new ET<>("http.client.request", HTTP_CLIENT_REQUEST_ID);

  /** An http downstream request */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, HttpClientRequest, Flow<Void>>> httpClientRequest() {
    return (EventType<BiFunction<RequestContext, HttpClientRequest, Flow<Void>>>)
        HTTP_CLIENT_REQUEST;
  }

  static final int FILE_LOADED_ID = 20;

  @SuppressWarnings("rawtypes")
  private static final EventType FILE_LOADED = new ET<>("file.loaded", FILE_LOADED_ID);

  /** A I/O file loaded */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String, Flow<Void>>> fileLoaded() {
    return (EventType<BiFunction<RequestContext, String, Flow<Void>>>) FILE_LOADED;
  }

  static final int REQUEST_SESSION_ID = 21;

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_SESSION = new ET<>("request.session", REQUEST_SESSION_ID);

  /** The session id of a request */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String, Flow<Void>>> requestSession() {
    return (EventType<BiFunction<RequestContext, String, Flow<Void>>>) REQUEST_SESSION;
  }

  static final int USER_ID = 22;

  @SuppressWarnings("rawtypes")
  private static final EventType USER = new ET<>("user", USER_ID);

  /** Triggered in every authenticated request to the application */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String, Flow<Void>>> user() {
    return (EventType<BiFunction<RequestContext, String, Flow<Void>>>) USER;
  }

  static final int LOGIN_EVENT_ID = 23;

  @SuppressWarnings("rawtypes")
  private static final EventType LOGIN_EVENT = new ET<>("login.event", LOGIN_EVENT_ID);

  @SuppressWarnings("unchecked")
  public EventType<TriFunction<RequestContext, LoginEvent, String, Flow<Void>>> loginEvent() {
    return (EventType<TriFunction<RequestContext, LoginEvent, String, Flow<Void>>>) LOGIN_EVENT;
  }

  static final int EXEC_CMD_ID = 24;

  @SuppressWarnings("rawtypes")
  private static final EventType EXEC_CMD = new ET<>("exec.cmd", EXEC_CMD_ID);

  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String[], Flow<Void>>> execCmd() {
    return (EventType<BiFunction<RequestContext, String[], Flow<Void>>>) EXEC_CMD;
  }

  static final int SHELL_CMD_ID = 25;

  @SuppressWarnings("rawtypes")
  private static final EventType SHELL_CMD = new ET<>("shell.cmd", SHELL_CMD_ID);

  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, String, Flow<Void>>> shellCmd() {
    return (EventType<BiFunction<RequestContext, String, Flow<Void>>>) SHELL_CMD;
  }

  static final int HTTP_ROUTE_ID = 26;

  @SuppressWarnings("rawtypes")
  private static final EventType HTTP_ROUTE = new ET<>("http.route", HTTP_ROUTE_ID);

  @SuppressWarnings("unchecked")
  public EventType<BiConsumer<RequestContext, String>> httpRoute() {
    return (EventType<BiConsumer<RequestContext, String>>) HTTP_ROUTE;
  }

  static final int RESPONSE_BODY_ID = 27;

  @SuppressWarnings("rawtypes")
  private static final EventType RESPONSE_BODY = new ET<>("response.body", RESPONSE_BODY_ID);

  /**
   * The original response body object used by the framework before being serialized to the response
   */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, Object, Flow<Void>>> responseBody() {
    return (EventType<BiFunction<RequestContext, Object, Flow<Void>>>) RESPONSE_BODY;
  }

  static final int HTTP_CLIENT_RESPONSE_ID = 28;

  @SuppressWarnings("rawtypes")
  private static final EventType HTTP_CLIENT_RESPONSE =
      new ET<>("http.client.response", HTTP_CLIENT_RESPONSE_ID);

  /** An http downstream response */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, HttpClientResponse, Flow<Void>>>
      httpClientResponse() {
    return (EventType<BiFunction<RequestContext, HttpClientResponse, Flow<Void>>>)
        HTTP_CLIENT_RESPONSE;
  }

  static final int HTTP_CLIENT_SAMPLING_ID = 29;

  @SuppressWarnings("rawtypes")
  private static final EventType HTTP_CLIENT_SAMPLING =
      new ET<>("http.client.sampling", HTTP_CLIENT_SAMPLING_ID);

  /** Check sampling status for a downstream request */
  @SuppressWarnings("unchecked")
  public EventType<BiFunction<RequestContext, Long, Flow<Boolean>>> httpClientSampling() {
    return (EventType<BiFunction<RequestContext, Long, Flow<Boolean>>>) HTTP_CLIENT_SAMPLING;
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
