package com.datadog.iast;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IASTModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalkerFactory;

public class IASTModuleImpl implements IASTModule {
  @Override
  public void onCipherAlgorithm(String algorithm) {}

  @Override
  public void onHashingAlgorithm(String algorithm) {
    if (Config.get().getWeakHashingAlgorithms().contains(algorithm.toUpperCase())) {
      // get StackTraceElement for the callee of MessageDigest
      StackTraceElement stackTraceElement =
          StackWalkerFactory.INSTANCE
              .walk()
              .filter(s -> !s.getClassName().equals("java.security.MessageDigest"))
              .findFirst()
              .get();

      Reporter.report(
          AgentTracer.activeSpan(),
          Vulnerability.builder()
              .type(VulnerabilityType.WEAK_HASH)
              .evidence(Evidence.forAlgorithm(algorithm))
              .location(Location.forStack(stackTraceElement))
              .build());
    }
  }
}
