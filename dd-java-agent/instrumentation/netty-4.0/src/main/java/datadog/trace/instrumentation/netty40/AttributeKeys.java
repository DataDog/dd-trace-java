package datadog.trace.instrumentation.netty40;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.trace.api.GenericClassValue;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import io.netty.util.AttributeKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class AttributeKeys {

  private static final ClassValue<ConcurrentHashMap<String, AttributeKey<?>>> MAPS =
      GenericClassValue.constructing(ConcurrentHashMap.class);

  public static final AttributeKey<AgentSpan> SPAN_ATTRIBUTE_KEY = attributeKey(DD_SPAN_ATTRIBUTE);

  public static final AttributeKey<AgentSpan> CLIENT_PARENT_ATTRIBUTE_KEY =
      attributeKey("datadog.client.parent.span");

  public static final AttributeKey<TraceScope.Continuation>
      CONNECT_PARENT_CONTINUATION_ATTRIBUTE_KEY =
          attributeKey("datadog.connect.parent.continuation");

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
      attributeKey = new AttributeKey<>(key);
      AttributeKey<T> predecessor = (AttributeKey<T>) map.putIfAbsent(key, attributeKey);
      if (null != predecessor) {
        attributeKey = predecessor;
      }
    }
    return attributeKey;
  }
}
