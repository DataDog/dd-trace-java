package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.WeakHashModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Locale;
import javax.annotation.Nonnull;

public class WeakHashModuleImpl extends SinkModuleBase implements WeakHashModule {

  private Config config;

  @Override
  public void registerDependencies(@Nonnull Dependencies dependencies) {
    super.registerDependencies(dependencies);
    config = dependencies.getConfig();
  }

  @Override
  public void onHashingAlgorithm(@Nonnull final String algorithm) {
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakHashAlgorithms().contains(algorithmId)) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    report(span, VulnerabilityType.WEAK_HASH, new Evidence(algorithm));
  }
}
