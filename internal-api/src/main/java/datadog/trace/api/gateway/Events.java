package datadog.trace.api.gateway;

import datadog.trace.api.Function;
import datadog.trace.api.function.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class Events {
  private static final AtomicInteger nextId = new AtomicInteger(0);

  /** A request started * */
  public static final EventType<Supplier<Flow<RequestContext>>> REQUEST_STARTED =
      new ET<>("request.started");

  /** A request ended * */
  public static final EventType<Function<? extends RequestContext, Flow<Void>>> REQUEST_ENDED =
      new ET<>("request.ended");

  /** A request header as a key and values separated by , */
  public static final EventType<TriConsumer<? extends RequestContext, String, String>>
      REQUEST_HEADER = new ET<>("server.request.header");

  /** All request headers have been provided */
  public static final EventType<Function<? extends RequestContext, Flow<Void>>>
      REQUEST_HEADER_DONE = new ET<>("server.request.header.done");

  /** The unparsed request uri, incl. the query string. */
  public static final EventType<BiFunction<? extends RequestContext, String, Flow<Void>>>
      REQUEST_URI_RAW = new ET<>("server.request.uri.raw");

  public static final int MAX_EVENTS = nextId.get();

  private static final class ET<T> extends EventType<T> {
    public ET(String type) {
      super(type, nextId.getAndIncrement());
    }
  }

  private Events() {}
}
