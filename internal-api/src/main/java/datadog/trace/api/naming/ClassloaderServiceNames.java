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
  private static class Lazy {
    private static final ClassloaderServiceNames INSTANCE = new ClassloaderServiceNames();
  }

  private final WeakHashMap<ClassLoader, String> weakCache = new WeakHashMap<>();
  private final String inferredServiceName =
      CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);
  private final boolean enabled =
      Config.get().isJeeSplitByDeployment() && !Config.get().isServiceNameSetByUser();

  private ClassloaderServiceNames() {}

  public static void addServiceName(@Nonnull ClassLoader classLoader, @Nonnull String serviceName) {
    if (Lazy.INSTANCE.enabled) {
      Lazy.INSTANCE.weakCache.put(classLoader, serviceName);
    }
  }

  @Nullable
  public static String maybeGet(@Nonnull ClassLoader classLoader) {
    if (Lazy.INSTANCE.enabled) {
      return Lazy.INSTANCE.weakCache.get(classLoader);
    }
    return null;
  }

  @Nullable
  public static String maybeGetForThread(@Nonnull final Thread thread) {
    return maybeGet(thread.getContextClassLoader());
  }

  public static boolean maybeSetToSpan(
      @Nonnull final AgentSpan span, @Nonnull final Thread thread) {
    return maybeSetToSpan(span, thread.getContextClassLoader());
  }

  public static boolean maybeSetToSpan(
      @Nonnull final AgentSpan span, @Nonnull final ClassLoader classLoader) {
    if (!Lazy.INSTANCE.enabled) {
      return false;
    }
    final String currentServiceName = span.getServiceName();
    if (currentServiceName != null
        && !currentServiceName.equals(Lazy.INSTANCE.inferredServiceName)) {
      return false;
    }
    final String service = maybeGet(classLoader);
    if (service != null) {
      span.setServiceName(service);
      ServiceNameCollector.get().addService(service);
      return true;
    }
    return false;
  }
}
