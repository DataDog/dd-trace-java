package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface PropagationModule extends IastModule {

  void taintIfInputIsTainted(@Nullable Object param1, @Nullable Object param2);

  void taintIfInputIsTainted(@Nullable String param1, @Nullable Object param2);

  void taintIfInputIsTainted(@Nonnull Object param1, @Nullable String param2);
}
