package com.datadog.featureflag;

import java.io.Closeable;

public interface FeatureFlagRemoteConfigService extends Closeable {

  void init();

  void close();
}
