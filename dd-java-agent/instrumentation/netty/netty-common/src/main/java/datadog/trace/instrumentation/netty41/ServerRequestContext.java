package datadog.trace.instrumentation.netty41;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-request server state stored on the channel until the matching response is written. */
public final class ServerRequestContext {
  /**
   * Returns whether a new server request can be tracked on this channel (and may disable server
   * tracing for the channel if the pending-context limit is exceeded).
   */
  public static boolean canTrackRequest(final AttributeMap attributes) {
    final Deque<ServerRequestContext> contexts =
        attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).get();
    return contexts == null || canAdd(attributes, contexts);
  }

  /** Adds a request context to the queue tail. */
  public static ServerRequestContext add(
      final AttributeMap attributes, final Context context, final String acceptHeader) {
    final Deque<ServerRequestContext> contexts = getOrCreate(attributes);
    if (!canAdd(attributes, contexts)) {
      return null;
    }
    final ServerRequestContext serverContext = new ServerRequestContext(context, acceptHeader);
    contexts.addLast(serverContext);
    // The deque is authoritative for server request/response matching. CONTEXT_ATTRIBUTE_KEY is a
    // context mirror of the current inbound request used by
    // NettyChannelHandlerContextInstrumentation.FireAdvice and copied to HTTP/2 stream channels by
    // Http2MultiplexHandlerStreamChannelInstrumentation.
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

  /** Returns the server request context for the current inbound request. */
  public static ServerRequestContext currentRequest(final AttributeMap attributes) {
    final Deque<ServerRequestContext> contexts =
        attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).get();
    return contexts == null || isPoisoned(contexts) ? null : contexts.peekLast();
  }

  /** Returns whether the channel is closing after an AppSec response block. */
  public static boolean isResponseBlocked(final AttributeMap attributes) {
    return attributes.attr(BLOCKED_RESPONSE_ATTRIBUTE_KEY).get() != null;
  }

  /** Marks the channel as closing after an AppSec response block. */
  public static void markResponseBlocked(final AttributeMap attributes) {
    attributes.attr(BLOCKED_RESPONSE_ATTRIBUTE_KEY).set(Boolean.TRUE);
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
        // No request-matching state remains. Drop the empty per-channel queue so idle
        // keep-alive or upgraded channels do not retain it after the last HTTP request;
        // getOrCreate will recreate it lazily if another request arrives.
        attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).remove();
      } else {
        // Keep the context mirror pointed at the current inbound request after removing an older
        // response context. It may still be copied to a new HTTP/2 stream channel.
        attributes.attr(CONTEXT_ATTRIBUTE_KEY).set(currentContext.tracingContext());
      }
    }
  }

  /** Closes all pending request contexts on channel close. */
  public static void closeAll(final AttributeMap attributes) {
    // The context mirror must not outlive the authoritative request queue.
    attributes.attr(CONTEXT_ATTRIBUTE_KEY).remove();
    attributes.attr(BLOCKED_RESPONSE_ATTRIBUTE_KEY).remove();
    close(attributes.attr(SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY).getAndRemove());
  }

  private static final int PIPELINING_LIMIT = 1000;

  private static final Logger log = LoggerFactory.getLogger(ServerRequestContext.class);

  /** Pending server request contexts for a channel. */
  private static final AttributeKey<Deque<ServerRequestContext>>
      SERVER_REQUEST_CONTEXTS_ATTRIBUTE_KEY =
          AttributeKeys.attributeKey("datadog.server.request.contexts");

  private static final AttributeKey<Boolean> BLOCKED_RESPONSE_ATTRIBUTE_KEY =
      AttributeKeys.attributeKey("datadog.server.blocked_response");

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
  private final String acceptHeader;
  private boolean responseStarted;
  private boolean responseAnalyzed;
  private Object deferredBlockResponse;

  public Context tracingContext() {
    return tracingContext;
  }

  public String acceptHeader() {
    return acceptHeader;
  }

  public boolean isResponseStarted() {
    return responseStarted;
  }

  public void markResponseStarted() {
    responseStarted = true;
  }

  public boolean isResponseAnalyzed() {
    return responseAnalyzed;
  }

  public void markResponseAnalyzed() {
    responseAnalyzed = true;
  }

  public Object deferredBlockResponse() {
    return deferredBlockResponse;
  }

  public void deferBlockResponse(final Object deferredBlockResponse) {
    this.deferredBlockResponse = deferredBlockResponse;
  }

  private ServerRequestContext(final Context tracingContext, final String acceptHeader) {
    this.tracingContext = tracingContext;
    this.acceptHeader = acceptHeader;
  }
}
