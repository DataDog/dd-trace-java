package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ReflectionInjectionModule extends IastModule {
  void onClassName(@Nullable String value);

  void onMethodName(
      @Nonnull Class<?> clazz, @Nonnull String methodName, @Nullable Class<?>... parameterTypes);

  void onFieldName(@Nonnull Class<?> clazz, @Nonnull String fieldName);
}
