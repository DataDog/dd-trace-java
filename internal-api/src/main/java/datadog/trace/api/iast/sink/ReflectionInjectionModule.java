package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface ReflectionInjectionModule extends IastModule {

  void onReflection(@Nullable String input);
}
