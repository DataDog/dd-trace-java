package datadog.trace.api.gateway;

import java.util.concurrent.atomic.AtomicInteger;

public final class Events {
  private static final AtomicInteger nextId = new AtomicInteger(0);


  /** A request started * */
  public static final EventType<RequestStarted> REQUEST_STARTED = new ET<>("request.started");

  public interface RequestStarted<C extends RequestContext> extends EventCallback {
    C get();
  }

  /** A request ended * */
  public static final EventType<RequestEnded> REQUEST_ENDED = new ET<>("request.ended");

  public interface RequestEnded<C extends RequestContext> extends EventCallback {
    void apply(C ctx);
  }

  /** A request header as a key and values separated by , */
  public static final EventType<RequestHeader> REQUEST_HEADER = new ET<>("server.request.header");

  public interface RequestHeader<C extends RequestContext> extends EventCallback {
    void accept(C ctx, String key, String value);
  }

  /** All request headers have been provided */
  public static final EventType<RequestHeaderDone> REQUEST_HEADER_DONE = new ET<>("server.request.header.done");

  public interface RequestHeaderDone<C extends RequestContext> extends EventCallback {
    Flow<C> apply(C ctx);
  }

  /** The unparsed request uri, incl. the query string. */
  public static final EventType<RequestUriRaw> REQUEST_URI_RAW = new ET<>("server.request.uri.raw");

  public interface RequestUriRaw<C extends RequestContext> extends EventCallback {
    Flow<C> apply(C ctx, String uri);
  }



  public static final int MAX_EVENTS = nextId.get();

  private static final class ET<T> extends EventType<T> {
    public ET(String type) {
      super(type, nextId.getAndIncrement());
    }
  }

  private Events() {}
}
