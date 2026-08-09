package com.datadog.featureflag;

import java.io.Closeable;

public interface ConfigurationSourceService extends Closeable {

  void init();

  void close();
}
