package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface CommandInjectionModule extends IastModule {

  void onRuntimeExec(@Nonnull String... command);

  void onRuntimeExec(@Nullable String[] env, @Nonnull String... command);

  void onProcessBuilderStart(@Nonnull List<String> command);
}
