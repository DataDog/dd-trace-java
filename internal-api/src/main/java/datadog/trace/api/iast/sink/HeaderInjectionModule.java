package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface HeaderInjectionModule extends IastModule {

  void onHeader(@Nonnull String name, String value);
}
