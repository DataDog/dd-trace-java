package datadog.trace.instrumentation.netty40;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.trace.api.Function;
import datadog.trace.bootstrap.WeakMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import io.netty.util.AttributeKey;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AttributeKeys {
  private static final WeakMap<ClassLoader, ConcurrentMap<String, AttributeKey<?>>> map =
      WeakMap.Implementation.DEFAULT.get();
  private static final Function<ClassLoader, ConcurrentMap<String, AttributeKey<?>>> mapSupplier =
      new Function<ClassLoader, ConcurrentMap<String, AttributeKey<?>>>() {
        @Override
        public ConcurrentMap<String, AttributeKey<?>> apply(final ClassLoader ignore) {
          return new ConcurrentHashMap<>();
        }
      };

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
  private static <T> AttributeKey<T> attributeKey(final String key) {
    final ConcurrentMap<String, AttributeKey<?>> classLoaderMap =
        map.computeIfAbsent(AttributeKey.class.getClassLoader(), mapSupplier);
    if (classLoaderMap.containsKey(key)) {
      return (AttributeKey<T>) classLoaderMap.get(key);
    }

    final AttributeKey<T> value = new AttributeKey<>(key);
    classLoaderMap.put(key, value);
    return value;
  }
}
