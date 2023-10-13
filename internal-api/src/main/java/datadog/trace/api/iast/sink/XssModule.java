package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface XssModule extends IastModule {

  void onXss(@Nonnull String s);

  void onXss(@Nonnull String s, @Nonnull String clazz, @Nonnull String method);

  void onXss(@Nonnull char[] array);

  void onXss(@Nonnull String format, @Nullable Object[] args);

  void onXss(@Nonnull CharSequence s, @Nullable String file, int line);
}
