package com.datadog.iast;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalker;
import datadog.trace.util.stacktrace.StackWalkerFactory;
import java.util.Locale;

public final class IastModuleImpl implements IastModule {

  private final Config config;
  private final Reporter reporter;
  private final StackWalker stackWalker = StackWalkerFactory.INSTANCE;

  public IastModuleImpl(final Config config, final Reporter reporter) {
    this.config = config;
    this.reporter = reporter;
  }

  public void onCipherAlgorithm(String algorithm) {
    if (algorithm == null) {
      return;
    }
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakCipherAlgorithms().matcher(algorithmId).matches()) {
      return;
    }
    // get StackTraceElement for the callee of MessageDigest
    StackTraceElement stackTraceElement =
        stackWalker.walk(
            stack ->
                stack
                    .filter(s -> !s.getClassName().equals("javax.crypto.Cipher"))
                    .findFirst()
                    .get());

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.WEAK_CIPHER,
            Location.forStack(stackTraceElement),
            new Evidence(algorithm));
    reporter.report(AgentTracer.activeSpan(), vulnerability);
  }

  public void onHashingAlgorithm(String algorithm) {
    if (algorithm == null) {
      return;
    }
    final String algorithmId = algorithm.toUpperCase(Locale.ROOT);
    if (!config.getIastWeakHashAlgorithms().contains(algorithmId)) {
      return;
    }
    // get StackTraceElement for the caller of MessageDigest
    StackTraceElement stackTraceElement =
        stackWalker.walk(
            stack ->
                stack
                    .filter(s -> !s.getClassName().equals("java.security.MessageDigest"))
                    .findFirst()
                    .get());

    Vulnerability vulnerability =
        new Vulnerability(
            VulnerabilityType.WEAK_HASH,
            Location.forStack(stackTraceElement),
            new Evidence(algorithm));
    reporter.report(AgentTracer.activeSpan(), vulnerability);
  }
}
