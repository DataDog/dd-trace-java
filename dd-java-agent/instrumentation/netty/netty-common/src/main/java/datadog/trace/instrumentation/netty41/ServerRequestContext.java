package datadog.trace.instrumentation.netty41;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;

import datadog.context.Context;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;
import java.util.ArrayDeque;
import java.util.Deque;

/** Per-request server state stored on the channel until the matching response is written. */
public final class ServerRequestContext {

  /** Pending server request contexts for a channel. */
  private static final AttributeKey<Deque<ServerRequestContext>>
      SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY =
          AttributeKeys.attributeKey("datadog.server.request.contexts");

  /** Adds a request context to the queue tail. */
  public static ServerRequestContext add(
      final AttributeMap attributes, final Context context, final HttpHeaders requestHeaders) {
    final ServerRequestContext serverContext = new ServerRequestContext(context, requestHeaders);
    getOrCreate(attributes).addLast(serverContext);
    // The deque is authoritative for server request/response matching. CONTEXT_ATTRIBUTE_KEY is a
    // legacy mirror of the current inbound request used by generic fire* activation.
    attributes.attr(CONTEXT_ATTRIBUTE_KEY).set(context);
    return serverContext;
  }

  /** Returns the server request context for the next response. */
  public static ServerRequestContext nextResponse(final AttributeMap attributes) {
    final Deque<ServerRequestContext> contexts =
        attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).get();
    // HTTP/1.1 responses are written in request order, including when requests are pipelined on one
    // connection.
    return contexts == null ? null : contexts.peekFirst();
  }

  /** Removes a completed or failed request context. */
  public static void remove(
      final AttributeMap attributes, final ServerRequestContext serverContext) {
    if (serverContext == null) {
      return;
    }
    final Deque<ServerRequestContext> contexts =
        attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).get();
    if (contexts != null) {
      if (contexts.peekFirst() == serverContext) {
        // Response completion consumes the queue head.
        contexts.pollFirst();
      } else {
        // Request-side failures normally remove the tail. Remove by value to cover later cleanup
        // after additional pipelined requests were queued.
        contexts.remove(serverContext);
      }
      final ServerRequestContext currentContext = contexts.peekLast();
      if (currentContext == null) {
        attributes.attr(CONTEXT_ATTRIBUTE_KEY).remove();
      } else {
        // Keep the legacy mirror pointed at the current inbound request after removing an older
        // response context.
        attributes.attr(CONTEXT_ATTRIBUTE_KEY).set(currentContext.tracingContext());
      }
    }
  }

  /** Removes all pending request contexts on channel close. */
  public static Deque<ServerRequestContext> removeAll(final AttributeMap attributes) {
    // The legacy mirror must not outlive the authoritative request queue.
    attributes.attr(CONTEXT_ATTRIBUTE_KEY).remove();
    return attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).getAndRemove();
  }

  /** Creates the per-channel server request context queue. */
  private static Deque<ServerRequestContext> getOrCreate(final AttributeMap attributes) {
    Deque<ServerRequestContext> contexts =
        attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).get();
    if (contexts == null) {
      // Netty serializes handler callbacks for a channel on its EventLoop, so this queue does not
      // need the allocation and atomic overhead of a concurrent collection.
      contexts = new ArrayDeque<>();
      attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).set(contexts);
    }
    return contexts;
  }

  private final Context tracingContext;
  private final HttpHeaders requestHeaders;
  private boolean responseAnalyzed;
  private boolean responseBlocked;

  public Context tracingContext() {
    return tracingContext;
  }

  public HttpHeaders requestHeaders() {
    return requestHeaders;
  }

  public boolean isResponseAnalyzed() {
    return responseAnalyzed;
  }

  public void markResponseAnalyzed() {
    responseAnalyzed = true;
  }

  public boolean isResponseBlocked() {
    return responseBlocked;
  }

  public void markResponseBlocked() {
    responseBlocked = true;
  }

  private ServerRequestContext(final Context tracingContext, final HttpHeaders requestHeaders) {
    this.tracingContext = tracingContext;
    this.requestHeaders = requestHeaders;
  }
}
