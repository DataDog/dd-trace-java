package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.Config;
import datadog.trace.api.iast.sink.WeakCipherModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Locale;
import javax.annotation.Nonnull;

public class WeakCipherModuleImpl extends SinkModuleBase implements WeakCipherModule {

  private Config config;

  @Override
  public void registerDependencies(@Nonnull Dependencies dependencies) {
    super.registerDependencies(dependencies);
    config = dependencies.getConfig();
  }

  @Override
  public void onCipherAlgorithm(@Nonnull final String algorithm) {
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakCipherAlgorithms().matcher(algorithmId).matches()) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
      return;
    }
    report(span, VulnerabilityType.WEAK_CIPHER, new Evidence(algorithm));
  }
}
