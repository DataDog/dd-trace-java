package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface UrlModule extends IastModule {

  void onDecode(@Nonnull String value, @Nullable String encoding, @Nonnull String result);
}
