package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface UntrustedDeserializationModule extends IastModule {

  void onObject(@Nullable Object object);
}
