package datadog.trace.api.openfeature.config;

import datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration;

public interface ServerConfigurationListener {

  void onConfiguration(ServerConfiguration configuration);
}
