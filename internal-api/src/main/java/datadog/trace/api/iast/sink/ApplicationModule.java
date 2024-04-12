package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.IastModule.OptOut;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

@OptOut
public interface ApplicationModule extends IastModule {

  void onRealPath(@Nullable String realPath);

  void checkSessionTrackingModes(@Nonnull Set<String> sessionTrackingModes);
}
