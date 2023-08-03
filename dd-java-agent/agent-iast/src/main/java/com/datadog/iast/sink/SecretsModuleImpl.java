package com.datadog.iast.sink;

import com.datadog.iast.overhead.Operations;
import com.datadog.iast.util.IastClassVisitor;
import datadog.trace.api.iast.sink.SecretsModule;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.objectweb.asm.ClassReader;

public class SecretsModuleImpl extends SinkModuleBase implements SecretsModule {

  private static final SecretMatcher GITHUB_TOKEN_MATCHER =
      new SecretMatcher("github-app-token", Pattern.compile("(ghu|ghs)_[0-9a-zA-Z]{36}"));

  private static final SecretMatcher[] matchers = {GITHUB_TOKEN_MATCHER};
  private static final int MIN_SECRET_LENGTH = 12;

  @Override
  public void onStringLiteral(
      @Nonnull final String value, @Nonnull final String clazz, final @Nonnull byte[] classFile) {

    String secretEvidence = getSecretEvidence(value);
    if (secretEvidence != null) {
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, null)) {
        return;
      }
      reportVulnerability(value, clazz, secretEvidence, classFile);
    }
  }

  private void reportVulnerability(
      final String value,
      final String clazz,
      final String secretEvidence,
      final @Nonnull byte[] classFile) {
    ClassReader classReader = new ClassReader(classFile);
    IastClassVisitor classVisitor = new IastClassVisitor(value, clazz, secretEvidence, reporter);
    classReader.accept(classVisitor, 0);
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
