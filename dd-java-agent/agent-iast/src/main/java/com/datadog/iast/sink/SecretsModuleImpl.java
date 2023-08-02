package com.datadog.iast.sink;

import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.Location;
import com.datadog.iast.model.Vulnerability;
import com.datadog.iast.model.VulnerabilityType;
import com.datadog.iast.overhead.Operations;
import datadog.trace.api.iast.sink.SecretsModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

public class SecretsModuleImpl extends SinkModuleBase implements SecretsModule {

  private static final SecretMatcher GITHUB_TOKEN_MATCHER =
      new SecretMatcher("github-app-token", Pattern.compile("(ghu|ghs)_[0-9a-zA-Z]{36}"));

  private static final SecretMatcher[] matchers = {GITHUB_TOKEN_MATCHER};
  private static final int MIN_SECRET_LENGTH = 12;

  @Override
  public void onStringLiteral(@Nonnull final String value, @Nonnull final String clazz) {

    String secretEvidence = getSecretEvidence(value);
    if (secretEvidence != null) {
      final AgentSpan span = AgentTracer.activeSpan();
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, span)) {
        return;
      }
      reporter.report(
          span,
          new Vulnerability(
              VulnerabilityType.HARDCODED_SECRET,
              Location.forSpanAndClass(span != null ? span.getSpanId() : 0, clazz),
              new Evidence(secretEvidence)));
    }
  }

  private String getSecretEvidence(String value) {
    if (value.length() >= MIN_SECRET_LENGTH) {
      for (SecretMatcher secretMatcher : matchers) {
        if (secretMatcher.matches(value)) {
          return secretMatcher.getRedactedEvidence();
        }
      }
    }
    return null;
  }

  static class SecretMatcher {

    private final String redactedEvidence;

    private final Pattern pattern;

    public SecretMatcher(final String redactedEvidence, final Pattern pattern) {
      this.redactedEvidence = redactedEvidence;
      this.pattern = pattern;
    }

    public String getRedactedEvidence() {
      return redactedEvidence;
    }

    public boolean matches(final String value) {
      return pattern.matcher(value).matches();
    }
  }
}
