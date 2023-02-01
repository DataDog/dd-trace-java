package com.datadog.ci;

import datadog.trace.api.Config;
import datadog.trace.api.ci.InstrumentationBridge;
import datadog.trace.bootstrap.instrumentation.ci.codeowners.CodeownersProvider;
import datadog.trace.bootstrap.instrumentation.ci.source.BestEfforSourcePathResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.CompilerAidedSourcePathResolver;
import datadog.trace.bootstrap.instrumentation.ci.source.MethodLinesResolverImpl;
import datadog.trace.bootstrap.instrumentation.ci.source.RepoIndexSourcePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CiVisibilitySystem {

  private static final Logger LOGGER = LoggerFactory.getLogger(CiVisibilitySystem.class);

  public static void start() {
    final Config config = Config.get();
    if (!config.isCiVisibilityEnabled()) {
      LOGGER.debug("CI Visibility is disabled");
      return;
    }

    InstrumentationBridge.setMethodLinesResolverFactory(MethodLinesResolverImpl::new);

    InstrumentationBridge.setCodeownersFactory(
        repoRoot -> new CodeownersProvider().build(repoRoot));

    InstrumentationBridge.setSourcePathResolverFactory(
        repoRoot ->
            new BestEfforSourcePathResolver(
                new CompilerAidedSourcePathResolver(repoRoot),
                new RepoIndexSourcePathResolver(repoRoot)));
  }
}
