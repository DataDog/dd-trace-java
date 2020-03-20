package datadog.trace.instrumentation.netty41;

import datadog.trace.bootstrap.WeakMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.netty41.client.HttpClientTracingHandler;
import datadog.trace.instrumentation.netty41.server.HttpServerTracingHandler;
import io.netty.util.AttributeKey;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AttributeKeys {
  private static WeakMap<ClassLoader, ConcurrentMap<String, AttributeKey<?>>> newWeakMap() {
    final Iterator<WeakMap.Implementation> providers =
        ServiceLoader.load(WeakMap.Implementation.class, null).iterator();
    if (providers.hasNext()) {
      final WeakMap.Implementation provider = providers.next();
      if (providers.hasNext()) {
        throw new IllegalStateException(
            "Only one implementation of WeakCache.Provider suppose to be in classpath");
      }
      return provider.get();
    }
    throw new IllegalStateException("Can't load implementation of WeakCache.Provider");
  }

  private static final WeakMap<ClassLoader, ConcurrentMap<String, AttributeKey<?>>> map =
      newWeakMap();

  private static final WeakMap.ValueSupplier<ClassLoader, ConcurrentMap<String, AttributeKey<?>>>
      mapSupplier =
          new WeakMap.ValueSupplier<ClassLoader, ConcurrentMap<String, AttributeKey<?>>>() {
            @Override
            public ConcurrentMap<String, AttributeKey<?>> get(final ClassLoader ignore) {
              return new ConcurrentHashMap<>();
            }
          };

  public static final AttributeKey<TraceScope.Continuation>
      PARENT_CONNECT_CONTINUATION_ATTRIBUTE_KEY =
          attributeKey("datadog.trace.instrumentation.netty41.parent.connect.continuation");

  /**
   * This constant is copied over to datadog.trace.instrumentation.ratpack.server.TracingHandler, so
   * if this changes, that must also change.
   */
  public static final AttributeKey<AgentSpan> SERVER_ATTRIBUTE_KEY =
      attributeKey(HttpServerTracingHandler.class.getName() + ".span");

  public static final AttributeKey<AgentSpan> CLIENT_ATTRIBUTE_KEY =
      attributeKey(HttpClientTracingHandler.class.getName() + ".span");

  public static final AttributeKey<AgentSpan> CLIENT_PARENT_ATTRIBUTE_KEY =
      attributeKey(HttpClientTracingHandler.class.getName() + ".parent");

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

    final AttributeKey<T> value = AttributeKey.valueOf(key);
    classLoaderMap.put(key, value);
    return value;
  }
}
