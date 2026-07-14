package datadog.trace.instrumentation.netty41;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-request server state stored on the channel until the matching response is written. */
public final class ServerRequestContext {
  /** Returns whether a new server request can be tracked on this channel. */
  public static boolean canTrackRequest(final AttributeMap attributes) {
    final Deque<ServerRequestContext> contexts =
        attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).get();
    return contexts == null || canAdd(attributes, contexts);
  }

  /** Adds a request context to the queue tail. */
  public static ServerRequestContext add(
      final AttributeMap attributes, final Context context, final HttpHeaders requestHeaders) {
    final Deque<ServerRequestContext> contexts = getOrCreate(attributes);
    if (!canAdd(attributes, contexts)) {
      return null;
    }
    final ServerRequestContext serverContext = new ServerRequestContext(context, requestHeaders);
    contexts.addLast(serverContext);
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
    return contexts == null || isPoisoned(contexts) ? null : contexts.peekFirst();
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
      if (isPoisoned(contexts)) {
        return;
      }
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

  /** Closes all pending request contexts on channel close. */
  public static void closeAll(final AttributeMap attributes) {
    // The legacy mirror must not outlive the authoritative request queue.
    attributes.attr(CONTEXT_ATTRIBUTE_KEY).remove();
    close(attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).getAndRemove());
  }

  private static final int PIPELINING_LIMIT = 1000;

  private static final Logger log = LoggerFactory.getLogger(ServerRequestContext.class);

  /** Pending server request contexts for a channel. */
  private static final AttributeKey<Deque<ServerRequestContext>>
      SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY =
          AttributeKeys.attributeKey("datadog.server.request.contexts");

  private static final Deque<ServerRequestContext> POISONED_CONTEXTS = new ArrayDeque<>(0);

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

  private static void close(final Deque<ServerRequestContext> contexts) {
    if (contexts == null || isPoisoned(contexts)) {
      return;
    }
    ServerRequestContext context;
    while ((context = contexts.pollFirst()) != null) {
      try {
        final AgentSpan span = AgentSpan.fromContext(context.tracingContext());
        if (span != null && span.phasedFinish()) {
          // These contexts no longer have a response handler path that can finish the span.
          span.publish();
        }
      } catch (final Throwable ignored) {
      }
    }
  }

  private static boolean canAdd(
      final AttributeMap attributes, final Deque<ServerRequestContext> contexts) {
    if (isPoisoned(contexts)) {
      return false;
    }
    // If this limit is exceeded, stop tracing on the channel and drain the deque. This suggests
    // contexts are not being removed, for example because the server stopped writing responses.
    if (contexts.size() >= PIPELINING_LIMIT) {
      final int pendingContexts = contexts.size();
      close(contexts);
      attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).set(POISONED_CONTEXTS);
      attributes.attr(CONTEXT_ATTRIBUTE_KEY).remove();
      log.error(
          "Too many pending Netty server request contexts on a channel; "
              + "closing {} contexts and disabling Netty server tracing on that channel "
              + "(limit: {})",
          pendingContexts,
          PIPELINING_LIMIT);
      return false;
    }
    return true;
  }

  private static boolean isPoisoned(final Deque<ServerRequestContext> contexts) {
    return contexts == POISONED_CONTEXTS;
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
