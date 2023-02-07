package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface PropagationModule extends IastModule {

  void taintIfInputIsTainted(@Nullable Object toTaint, @Nullable Object input);

  void taintIfInputIsTainted(@Nullable String toTaint, @Nullable Object input);

  void taintIfInputIsTainted(@Nonnull Object toTaint, @Nullable String input);
}
