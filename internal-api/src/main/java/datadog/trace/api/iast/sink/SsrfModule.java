package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface SsrfModule extends IastModule {

  void onURLConnection(@Nullable Object url);

  void onURLConnection(@Nullable String url, @Nullable Object host, @Nullable Object uri);
}
