package com.datadog.iast.sink;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.WeakHashModule;
import java.util.Locale;
import javax.annotation.Nonnull;

public class WeakHashModuleImpl extends SinkModuleBase implements WeakHashModule {

  private Config config;

  public WeakHashModuleImpl(final Dependencies dependencies) {
    super(dependencies);
    config = dependencies.getConfig();
  }

  @Override
  public void onHashingAlgorithm(@Nonnull final String algorithm) {
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakHashAlgorithms().contains(algorithmId)) {
      return;
    }
    report(VulnerabilityType.WEAK_HASH, new Evidence(algorithm));
  }
}
