package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.WeakCipherModule;
import java.util.Locale;
import javax.annotation.Nonnull;

public class WeakCipherModuleImpl extends SinkModuleBase implements WeakCipherModule {

  private Config config;

  public WeakCipherModuleImpl(final Dependencies dependencies) {
    super(dependencies);
    config = dependencies.getConfig();
  }

  @Override
  public void onCipherAlgorithm(@Nonnull final String algorithm) {
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakCipherAlgorithms().matcher(algorithmId).matches()) {
      return;
    }
    report(VulnerabilityType.WEAK_CIPHER, new Evidence(algorithm));
  }
}
