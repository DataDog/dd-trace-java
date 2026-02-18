package datadog.trace.api;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ClassloaderConfigurationOverrides {
  public static final String DATADOG_TAGS_PREFIX = "datadog/tags/";
  public static final String DATADOG_TAGS_JNDI_PREFIX = "java:comp/env/" + DATADOG_TAGS_PREFIX;
  // not final for testing purposes
  static boolean CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT =
      Config.get().isJeeSplitByDeployment() && !Config.get().isServiceNameSetByUser();

  static class Lazy {
    static final ClassloaderConfigurationOverrides INSTANCE =
        new ClassloaderConfigurationOverrides();
  }

  public static class ContextualInfo {
    private final String serviceName;
    private final String serviceNameSource;
    private final Map<String, Object> tags = new HashMap<>();

    public ContextualInfo(String serviceName, String source) {
      this.serviceName = serviceName;
      this.serviceNameSource = source;
    }

    public String getServiceName() {
      return serviceName;
    }

    public String getServiceNameSource() {
      return serviceNameSource;
    }

    public void addTag(String name, Object value) {
      tags.put(name, value);
    }

    public Map<String, Object> getTags() {
      return Collections.unmodifiableMap(tags);
    }
  }

  private static final Function<ClassLoader, ContextualInfo> EMPTY_CONTEXTUAL_INFO_ADDER =
      ignored -> new ContextualInfo(null, null);

  private final WeakHashMap<ClassLoader, ContextualInfo> weakCache = new WeakHashMap<>();
  private final String inferredServiceName =
      CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);

  private static volatile boolean atLeastOneEntry;
  private static final Lock lock = new ReentrantLock();

  private ClassloaderConfigurationOverrides() {}

  public static void addContextualInfo(ClassLoader classLoader, ContextualInfo contextualInfo) {
    try {
      lock.lock();
      Lazy.INSTANCE.weakCache.put(classLoader, contextualInfo);
      atLeastOneEntry = true;
    } finally {
      lock.unlock();
    }
  }

  public static ContextualInfo maybeCreateContextualInfo(ClassLoader classLoader) {
    try {
      lock.lock();
      final ContextualInfo ret =
          Lazy.INSTANCE.weakCache.computeIfAbsent(classLoader, EMPTY_CONTEXTUAL_INFO_ADDER);
      atLeastOneEntry = true;
      return ret;
    } finally {
      lock.unlock();
    }
  }

  @Nullable
  public static ContextualInfo withPinnedServiceName(
      ClassLoader classLoader, String serviceName, String serviceNameSource) {
    if (!CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT) {
      return null;
    }
    final ContextualInfo contextualInfo = new ContextualInfo(serviceName, serviceNameSource);
    addContextualInfo(classLoader, contextualInfo);
    return contextualInfo;
  }

  @Nullable
  public static ContextualInfo maybeGetContextualInfo(ClassLoader classLoader) {
    if (atLeastOneEntry) {
      return Lazy.INSTANCE.weakCache.get(classLoader);
    }
    return null;
  }

  /**
   * Fetches the contextual information linked to the current thread's context classloader.
   *
   * @return a nullable service name.
   */
  @Nullable
  public static ContextualInfo maybeGetContextualInfo() {
    if (atLeastOneEntry) {
      return maybeGetContextualInfo(Thread.currentThread().getContextClassLoader());
    }
    return null;
  }

  /**
   * Enriches the provided spans according to the service name and tags linked to the current
   * thread's classloader contextual information.
   *
   * @param span a nonnull span
   */
  public static void maybeEnrichSpan(@Nonnull final AgentSpan span) {
    if (atLeastOneEntry) {
      maybeEnrichSpan(span, Thread.currentThread().getContextClassLoader());
    }
  }

  public static void maybeEnrichSpan(
      @Nonnull final AgentSpan span, @Nonnull final ClassLoader classLoader) {
    final ContextualInfo contextualInfo = maybeGetContextualInfo(classLoader);
    if (contextualInfo == null) {
      return;
    }
    final String serviceName = contextualInfo.getServiceName();
    if (CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT && serviceName != null && !serviceName.isEmpty()) {
      final String currentServiceName = span.getServiceName();
      if (currentServiceName == null
          || currentServiceName.equals(Lazy.INSTANCE.inferredServiceName)) {
        span.setServiceName(serviceName, contextualInfo.getServiceNameSource());
        ServiceNameCollector.get().addService(serviceName);
      }
    }
    contextualInfo.getTags().forEach(span::setTag);
  }
}
