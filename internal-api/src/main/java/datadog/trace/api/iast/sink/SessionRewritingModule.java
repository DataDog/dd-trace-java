package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.util.Set;
import javax.annotation.Nonnull;

public interface SessionRewritingModule extends IastModule {
  void checkSessionTrackingModes(@Nonnull Set<String> sessionTrackingModes);
}
