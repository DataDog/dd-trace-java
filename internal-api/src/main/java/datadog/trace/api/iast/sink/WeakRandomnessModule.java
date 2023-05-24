package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface WeakRandomnessModule extends IastModule {

  void onWeakRandom(@Nonnull final Class<?> instance);
}
