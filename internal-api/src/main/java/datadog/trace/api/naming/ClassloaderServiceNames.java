package datadog.trace.api.naming;

import datadog.trace.api.Config;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.env.CapturedEnvironment;
import datadog.trace.api.remoteconfig.ServiceNameCollector;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ClassloaderServiceNames {
  private static class Lazy {
    private static final ClassloaderServiceNames INSTANCE = new ClassloaderServiceNames();
  }

  private final WeakHashMap<ClassLoader, Supplier<String>> weakCache = new WeakHashMap<>();
  private final String inferredServiceName =
      CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME);
  private final boolean enabled =
      Config.get().isJeeSplitByDeployment() && !Config.get().isServiceNameSetByUser();

  private ClassloaderServiceNames() {}

  public static void addIfMissing(
      @Nonnull ClassLoader classLoader,
      @Nonnull Function<? super ClassLoader, Supplier<String>> adder) {
    if (Lazy.INSTANCE.enabled) {
      Lazy.INSTANCE.weakCache.computeIfAbsent(classLoader, adder);
    }
  }

  @Nullable
  public static String maybeGet(@Nonnull ClassLoader classLoader) {
    if (Lazy.INSTANCE.enabled) {
      final Supplier<String> supplier = Lazy.INSTANCE.weakCache.get(classLoader);
      if (supplier != null) {
        return supplier.get();
      }
    }
    return null;
  }

  @Nullable
  public static String maybeGetForThread(@Nonnull final Thread thread) {
    return maybeGet(thread.getContextClassLoader());
  }

  public static boolean maybeSetToSpan(
      @Nonnull final Consumer<String> setter,
      @Nonnull final Supplier<String> getter,
      @Nonnull final Thread thread) {
    return maybeSetToSpan(setter, getter, thread.getContextClassLoader());
  }

  public static boolean maybeSetToSpan(
      @Nonnull final Consumer<String> setter,
      @Nonnull final Supplier<String> getter,
      @Nonnull final ClassLoader classLoader) {
    if (!Lazy.INSTANCE.enabled) {
      return false;
    }
    final String currentServiceName = getter.get();
    if (currentServiceName != null
        && !currentServiceName.equals(Lazy.INSTANCE.inferredServiceName)) {
      return false;
    }
    final String service = maybeGet(classLoader);
    if (service != null) {
      setter.accept(service);
      ServiceNameCollector.get().addService(service);
      return true;
    }
    return false;
  }
}
