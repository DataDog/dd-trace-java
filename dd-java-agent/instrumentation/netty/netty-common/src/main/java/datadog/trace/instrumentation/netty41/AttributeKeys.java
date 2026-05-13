package datadog.trace.instrumentation.netty41;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;

import datadog.context.Context;
import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.util.AttributeKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AttributeKeys {

  private static final ClassValue<ConcurrentHashMap<String, AttributeKey<?>>> MAPS =
      GenericClassValue.constructing(ConcurrentHashMap.class);

  public static final AttributeKey<Context> CONTEXT_ATTRIBUTE_KEY =
      attributeKey(DD_CONTEXT_ATTRIBUTE);

  /**
   * Stores the context of the currently-streaming (chunked) response. Set when the HTTP response
   * headers are sent, cleared when LastHttpContent is processed. Using a separate key (instead of
   * CONTEXT_ATTRIBUTE_KEY) avoids a keep-alive race: Netty can process the next request's
   * channelRead before the current response's LastHttpContent write task runs, overwriting
   * CONTEXT_ATTRIBUTE_KEY with the new request's span.
   */
  public static final AttributeKey<Context> STREAMING_CONTEXT_KEY =
      attributeKey("datadog.server.streaming.context");

  public static final AttributeKey<AgentSpan> CLIENT_PARENT_ATTRIBUTE_KEY =
      attributeKey("datadog.client.parent.span");

  public static final AttributeKey<AgentScope.Continuation>
      CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY =
          attributeKey("datadog.connect.parent.continuation");

  public static final AttributeKey<Context> PARENT_CONTEXT_ATTRIBUTE_KEY =
      attributeKey("datadog.server.parent-context");

  public static final AttributeKey<HttpHeaders> REQUEST_HEADERS_ATTRIBUTE_KEY =
      attributeKey("datadog.server.request.headers");

  public static final AttributeKey<Boolean> ANALYZED_RESPONSE_KEY =
      attributeKey("datadog.server.analyzed_response");

  public static final AttributeKey<Boolean> BLOCKED_RESPONSE_KEY =
      attributeKey("datadog.server.blocked_response");

  public static final AttributeKey<HandlerContext.Sender> WEBSOCKET_SENDER_HANDLER_CONTEXT =
      attributeKey("datadog.server.websocket.sender.handler_context");

  public static final AttributeKey<HandlerContext.Receiver> WEBSOCKET_RECEIVER_HANDLER_CONTEXT =
      attributeKey("datadog.server.websocket.receiver.handler_context");

  /**
   * Generate an attribute key or reuse the one existing in the global app map. This implementation
   * creates attributes only once even if the current class is loaded by several class loaders and
   * prevents an issue with Apache Atlas project were this class loaded by multiple class loaders,
   * while the Attribute class is loaded by a third class loader and used internally for the
   * cassandra driver.
   */
  @SuppressWarnings("unchecked")
  private static <T> AttributeKey<T> attributeKey(final String key) {
    ConcurrentMap<String, AttributeKey<?>> map = MAPS.get(AttributeKey.class);
    AttributeKey<T> attributeKey = (AttributeKey<T>) map.get(key);
    if (null == attributeKey) {
      attributeKey = AttributeKey.valueOf(key);
      AttributeKey<T> predecessor = (AttributeKey<T>) map.putIfAbsent(key, attributeKey);
      if (null != predecessor) {
        attributeKey = predecessor;
      }
    }
    return attributeKey;
  }
}
