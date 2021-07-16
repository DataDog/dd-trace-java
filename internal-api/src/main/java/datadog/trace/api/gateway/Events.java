package datadog.trace.api.gateway;

import datadog.trace.api.Function;
import datadog.trace.api.function.*;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.util.concurrent.atomic.AtomicInteger;

/** All known {@code EventType} that the {@code InstrumentationGateway} can handle. */
public final class Events {
  private static final AtomicInteger nextId = new AtomicInteger(0);

  // The IDs that we define here need to be compile time constants for Java
  // to be able to do a switch statement, hence we can't use nextId in the
  // definitions of them :(
  // There is a double check in the ET constructor that makes sure that we
  // assign the IDs correctly.

  public static final int REQUEST_STARTED_ID = 0;
  /** A request started * */
  public static final EventType<Supplier<Flow<RequestContext>>> REQUEST_STARTED =
      new ET<>("request.started", REQUEST_STARTED_ID);

  public static final int REQUEST_ENDED_ID = 1;
  /** A request ended * */
  public static final EventType<Function<RequestContext, Flow<Void>>> REQUEST_ENDED =
      new ET<>("request.ended", REQUEST_ENDED_ID);

  public static final int REQUEST_HEADER_ID = 2;
  /** A request header as a key and values separated by , */
  public static final EventType<TriConsumer<RequestContext, String, String>> REQUEST_HEADER =
      new ET<>("server.request.header", REQUEST_HEADER_ID);

  public static final int REQUEST_HEADER_DONE_ID = 3;
  /** All request headers have been provided */
  public static final EventType<Function<RequestContext, Flow<Void>>> REQUEST_HEADER_DONE =
      new ET<>("server.request.header.done", REQUEST_HEADER_DONE_ID);

  public static final int REQUEST_URI_RAW_ID = 4;
  /** The URIDataAdapter for the request. */
  public static final EventType<BiFunction<RequestContext, URIDataAdapter, Flow<Void>>>
      REQUEST_URI_RAW = new ET<>("server.request.uri.raw", REQUEST_URI_RAW_ID);

  public static final int MAX_EVENTS = nextId.get();

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
}
