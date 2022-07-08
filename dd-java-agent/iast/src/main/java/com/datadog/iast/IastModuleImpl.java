package com.datadog.iast;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.Config;
import datadog.trace.api.iast.IastModule;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.util.stacktrace.StackWalkerFactory;

public class IastModuleImpl implements IastModule {
  public void onCipherAlgorithm(String algorithm) {
    if (Config.get().getWeakCipherAlgorithms().contains(algorithm.toUpperCase())) {
      // get StackTraceElement for the callee of MessageDigest
      StackTraceElement stackTraceElement =
          StackWalkerFactory.INSTANCE.walk(
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
      Reporter.report(AgentTracer.activeSpan(), vulnerability);
    }
  }

  public void onHashingAlgorithm(String algorithm) {
    if (Config.get().getWeakHashingAlgorithms().contains(algorithm.toUpperCase())) {
      // get StackTraceElement for the callee of MessageDigest
      StackTraceElement stackTraceElement =
          StackWalkerFactory.INSTANCE.walk(
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
      Reporter.report(AgentTracer.activeSpan(), vulnerability);
    }
  }
}
