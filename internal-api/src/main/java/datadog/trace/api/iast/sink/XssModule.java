package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface XssModule extends IastModule {

  void onXss(@Nonnull String s);

  void onXss(@Nonnull char[] array);
}
