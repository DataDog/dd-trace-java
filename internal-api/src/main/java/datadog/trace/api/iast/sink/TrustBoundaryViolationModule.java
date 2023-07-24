package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;

public interface TrustBoundaryViolationModule extends IastModule {

  void onSessionValue(@Nonnull String name, Object value);
}
