package com.datadog.featureflag;

import com.datadog.featureflag.ufc.v1.ServerConfiguration;
import java.io.Closeable;
import java.util.function.Consumer;

public interface FeatureFlagRemoteConfigService extends Closeable {

  void init();

  void close();

  void addConsumer(Consumer<ServerConfiguration> configuration);
}
