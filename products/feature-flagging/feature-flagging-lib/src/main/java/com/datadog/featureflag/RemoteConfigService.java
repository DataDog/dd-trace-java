package com.datadog.featureflag;

import java.io.Closeable;

public interface RemoteConfigService extends Closeable {

  void init();

  void close();
}
