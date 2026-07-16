package datadog.trace.instrumentation.netty41;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;

import datadog.context.Context;
import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.util.AttributeKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AttributeKeys {

  private static final ClassValue<ConcurrentHashMap<String, AttributeKey<?>>> MAPS =
      GenericClassValue.constructing(ConcurrentHashMap.class);

  public static final AttributeKey<Context> CONTEXT_ATTRIBUTE_KEY =
      attributeKey(DD_CONTEXT_ATTRIBUTE);

  public static final AttributeKey<AgentSpan> CLIENT_PARENT_ATTRIBUTE_KEY =
      attributeKey("datadog.client.parent.span");

  public static final AttributeKey<AgentScope.Continuation>
      CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY =
          attributeKey("datadog.connect.parent.continuation");

  public static final AttributeKey<Boolean> HTTP2_CONNECTION_CODEC_ATTRIBUTE_KEY =
      attributeKey("datadog.http2.connection.codec");

  public static final AttributeKey<Boolean> HTTP2_STREAM_CODEC_ATTRIBUTE_KEY =
      attributeKey("datadog.http2.stream.codec");

  public static final AttributeKey<Context> PARENT_CONTEXT_ATTRIBUTE_KEY =
      attributeKey("datadog.server.parent-context");

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
  static <T> AttributeKey<T> attributeKey(final String key) {
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
