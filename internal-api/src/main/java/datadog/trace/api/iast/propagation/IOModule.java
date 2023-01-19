package datadog.trace.api.iast.propagation;

import datadog.trace.api.iast.IastModule;
import java.io.InputStream;
import javax.annotation.Nullable;

public interface IOModule extends IastModule {

  void onConstruct(@Nullable InputStream inputStream, @Nullable InputStream result);
}
