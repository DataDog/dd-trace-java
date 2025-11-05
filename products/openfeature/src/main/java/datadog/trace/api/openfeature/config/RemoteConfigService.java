package datadog.trace.api.openfeature.config;

import java.io.Closeable;

public interface RemoteConfigService extends Closeable {

  void init();

  void close();
}
