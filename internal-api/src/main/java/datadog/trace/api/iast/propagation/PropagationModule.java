package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface PropagationModule extends IastModule {

  void taintIfInputIsTainted(@Nullable Object toTaint, @Nullable Object input);

  void taintIfInputIsTainted(@Nullable String toTaint, @Nullable Object input);

  void taintIfInputIsTainted(
      byte origin, @Nullable String name, @Nullable String toTaint, @Nullable Object input);

  void taint(byte origin, @Nullable Object... toTaint);
}
