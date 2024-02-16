package datadog.trace.instrumentation.armeria.jetty;

import datadog.trace.api.GenericClassValue;
import io.netty.util.AttributeKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.eclipse.jetty.server.HttpChannel;

public final class AttributeKeys {

  private static final ClassValue<ConcurrentHashMap<String, AttributeKey<?>>> MAPS =
      GenericClassValue.constructing(ConcurrentHashMap.class);

  public static final AttributeKey<HttpChannel> HTTP_CHANNEL_ATTRIBUTE_KEY =
      attributeKey("dd.armeria.jetty.channel");

  /**
   * Generate an attribute key or reuse the one existing in the global app map. This implementation
   * creates attributes only once even if the current class is loaded by several class loaders and
   * prevents an issue with Apache Atlas project were this class loaded by multiple class loaders,
   * while the Attribute class is loaded by a third class loader and used internally for the
   * cassandra driver. note: drawn from netty module instrumentation
   */
  @SuppressWarnings("unchecked")
  private static <T> AttributeKey<T> attributeKey(final String key) {
    ConcurrentMap<String, AttributeKey<?>> map = MAPS.get(AttributeKey.class);
    AttributeKey<T> attributeKey = (AttributeKey<T>) map.get(key);
    if (null == attributeKey) {
      attributeKey = AttributeKey.newInstance(key);
      AttributeKey<T> predecessor = (AttributeKey<T>) map.putIfAbsent(key, attributeKey);
      if (null != predecessor) {
        attributeKey = predecessor;
      }
    }
    return attributeKey;
  }
}
