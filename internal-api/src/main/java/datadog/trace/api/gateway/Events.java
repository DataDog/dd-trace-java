package datadog.trace.api.gateway;

import datadog.trace.api.Function;
import datadog.trace.api.function.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class Events {
  private static final AtomicInteger nextId = new AtomicInteger(0);

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_STARTED = new ET<>("request.started");

  /** A request started * */
  @SuppressWarnings("unchecked")
  public static <C extends RequestContext> EventType<Supplier<Flow<C>>> requestStarted() {
    return (EventType<Supplier<Flow<C>>>) REQUEST_STARTED;
  }

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_ENDED = new ET<>("request.ended");

  /** A request ended * */
  @SuppressWarnings("unchecked")
  public static <C extends RequestContext> EventType<Function<C, Flow<Void>>> requestEnded() {
    return (EventType<Function<C, Flow<Void>>>) REQUEST_ENDED;
  }

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_HEADER = new ET<>("server.request.header");

  /** A request header as a key and values separated by , */
  @SuppressWarnings("unchecked")
  public static <C extends RequestContext>
      EventType<TriConsumer<C, String, String>> requestHeader() {
    return (EventType<TriConsumer<C, String, String>>) REQUEST_HEADER;
  }

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_HEADER_DONE = new ET<>("server.request.header.done");

  /** All request headers have been provided */
  @SuppressWarnings("unchecked")
  public static <C extends RequestContext> EventType<Function<C, Flow<Void>>> requestHeaderDone() {
    return (EventType<Function<C, Flow<Void>>>) REQUEST_HEADER_DONE;
  }

  @SuppressWarnings("rawtypes")
  private static final EventType REQUEST_URI_RAW = new ET<>("server.request.uri.raw");

  /** The unparsed request uri, incl. the query string. */
  @SuppressWarnings("unchecked")
  public static <C extends RequestContext>
      EventType<BiFunction<C, String, Flow<Void>>> requestUriRaw() {
    return (EventType<BiFunction<C, String, Flow<Void>>>) REQUEST_URI_RAW;
  }

  public static final int MAX_EVENTS = nextId.get();

  private static final class ET<T> extends EventType<T> {
    public ET(String type) {
      super(type, nextId.getAndIncrement());
    }
  }

  private Events() {}
}
