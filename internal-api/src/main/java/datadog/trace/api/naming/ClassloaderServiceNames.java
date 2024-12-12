package datadog.trace.api.naming;

import datadog.trace.api.Config;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ClassloaderServiceNames {
  private static final boolean ENABLED =
      Config.get().isJeeSplitByDeployment() && !Config.get().isServiceNameSetByUser();

  private static class Lazy {
    private static final ClassloaderServiceNames INSTANCE = new ClassloaderServiceNames();
  }

  private final WeakHashMap<ClassLoader, String> weakCache = new WeakHashMap<>();
  private final String inferredServiceName =
      CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);

  private ClassloaderServiceNames() {}

  public static void addServiceName(@Nonnull ClassLoader classLoader, @Nonnull String serviceName) {
    if (ENABLED) {
      Lazy.INSTANCE.weakCache.put(classLoader, serviceName);
    }
  }

  @Nullable
  public static String maybeGet(@Nonnull ClassLoader classLoader) {
    if (ENABLED) {
      return Lazy.INSTANCE.weakCache.get(classLoader);
    }
    return null;
  }

  /**
   * Fetches the service name linked to the current thread's context classloader.
   *
   * @return a nullable service name.
   */
  @Nullable
  public static String maybeGetForCurrentThread() {
    return maybeGet(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Sets the service name to the provided spans according to the service name linked to the current
   * thread's classloader.
   *
   * @param span a nonnull span
   */
  public static void maybeSetToSpan(@Nonnull final AgentSpan span) {
    maybeSetToSpan(span, Thread.currentThread().getContextClassLoader());
  }

  public static void maybeSetToSpan(
      @Nonnull final AgentSpan span, @Nonnull final ClassLoader classLoader) {
    if (!ENABLED) {
      return;
    }
    final String currentServiceName = span.getServiceName();
    if (currentServiceName != null
        && !currentServiceName.equals(Lazy.INSTANCE.inferredServiceName)) {
      return;
    }
    final String service = maybeGet(classLoader);
    if (service != null) {
      span.setServiceName(service);
      ServiceNameCollector.get().addService(service);
    }
  }
}
