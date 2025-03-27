package com.datadog.appsec.config;

import com.datadog.ddwaf.WafBuilder;
import java.io.Closeable;

public interface AppSecConfigService extends Closeable {
  void close();

  void init(WafBuilder wafBuilder);

  TransactionalAppSecModuleConfigurer createAppSecModuleConfigurer();

  interface TransactionalAppSecModuleConfigurer extends AppSecModuleConfigurer {
    void commit();
  }
}
