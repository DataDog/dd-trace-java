package com.datadog.appsec.config;

import java.io.Closeable;

public interface AppSecConfigService extends Closeable {
  void init();

  void close();

  TransactionalAppSecModuleConfigurer createAppSecModuleConfigurer();

  interface TransactionalAppSecModuleConfigurer extends AppSecModuleConfigurer {
    void commit();
  }
}
