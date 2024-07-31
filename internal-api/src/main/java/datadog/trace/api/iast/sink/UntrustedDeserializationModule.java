package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import java.io.InputStream;
import javax.annotation.Nullable;

public interface UntrustedDeserializationModule extends IastModule {

  void onInputStream(@Nullable InputStream is);
}
