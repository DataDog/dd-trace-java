package datadog.trace.api;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ClassloaderConfigurationOverrides {
  public static final String DATADOG_TAGS_PREFIX = "datadog/tags/";
  public static final String DATADOG_TAGS_JNDI_PREFIX = "java:comp/env/" + DATADOG_TAGS_PREFIX;
  private static final boolean CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT =
      Config.get().isJeeSplitByDeployment() && !Config.get().isServiceNameSetByUser();

  private static class Lazy {
    private static final ClassloaderConfigurationOverrides INSTANCE =
        new ClassloaderConfigurationOverrides();
  }

  public static class ContextualInfo {
    private final String serviceName;
    private final Map<String, Object> tags = new HashMap<>();

    public ContextualInfo(String serviceName) {
      this.serviceName = serviceName;
    }

    public String getServiceName() {
      return serviceName;
    }

    public void addTag(String name, Object value) {
      tags.put(name, value);
    }

    public Map<String, Object> getTags() {
      return Collections.unmodifiableMap(tags);
    }
  }

  private static Function<ClassLoader, ContextualInfo> EMPTY_CONTEXTUAL_INFO_ADDER =
      ignored -> new ContextualInfo(null);

  private final WeakHashMap<ClassLoader, ContextualInfo> weakCache = new WeakHashMap<>();
  private final String inferredServiceName =
      CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);

  private ClassloaderConfigurationOverrides() {}

  public static void addContextualInfo(
      @Nonnull ClassLoader classLoader, @Nonnull ContextualInfo contextualInfo) {
    Lazy.INSTANCE.weakCache.put(classLoader, contextualInfo);
  }

  @Nullable
  public static ContextualInfo maybeGetContextualInfo(@Nonnull ClassLoader classLoader) {
    return Lazy.INSTANCE.weakCache.get(classLoader);
  }

  /**
   * Fetches the contextual information linked to the current thread's context classloader.
   *
   * @return a nullable service name.
   */
  @Nullable
  public static ContextualInfo maybeGetContextualInfo() {
    return maybeGetContextualInfo(Thread.currentThread().getContextClassLoader());
  }

  @Nonnull
  public static ContextualInfo getOrAddEmpty(@Nonnull ClassLoader classLoader) {
    return Lazy.INSTANCE.weakCache.computeIfAbsent(classLoader, EMPTY_CONTEXTUAL_INFO_ADDER);
  }

  /**
   * Enriches the provided spans according to the service name and tags linked to the current
   * thread's classloader contextual information.
   *
   * @param span a nonnull span
   */
  public static void maybeEnrichSpan(@Nonnull final AgentSpan span) {
    maybeEnrichSpan(span, Thread.currentThread().getContextClassLoader());
  }

  public static void maybeEnrichSpan(
      @Nonnull final AgentSpan span, @Nonnull final ClassLoader classLoader) {
    final ContextualInfo contextualInfo = maybeGetContextualInfo(classLoader);
    if (contextualInfo == null) {
      return;
    }
    if (CAN_SPLIT_SERVICE_NAME_BY_DEPLOYMENT
        && contextualInfo.serviceName != null
        && !contextualInfo.getServiceName().isEmpty()) {
      final String currentServiceName = span.getServiceName();
      if (currentServiceName == null
          || currentServiceName.equals(Lazy.INSTANCE.inferredServiceName)) {
        final String serviceName = contextualInfo.getServiceName();

        span.setServiceName(serviceName);
        ServiceNameCollector.get().addService(serviceName);
      }
    }
    for (final Map.Entry<String, Object> entry : contextualInfo.getTags().entrySet()) {
      span.setTag(entry.getKey(), entry.getValue());
    }
  }
}
