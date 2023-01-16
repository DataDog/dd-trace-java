package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface PropagationModule extends IastModule {

  void taintParam1IfParam2IsTainted(@Nullable Object param1, @Nullable Object param2);

  void taintParam1IfParam2IsTainted(@Nullable String param1, @Nullable Object param2);

  void taintParam1IfParam2IsTainted(@Nonnull Object param1, @Nullable String param2);
}
