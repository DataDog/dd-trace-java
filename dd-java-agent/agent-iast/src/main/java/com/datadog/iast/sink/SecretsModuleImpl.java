package com.datadog.iast.sink;

import com.datadog.iast.overhead.Operations;
import com.datadog.iast.util.IastClassVisitor;
import datadog.trace.api.iast.sink.SecretsModule;

import java.util.HashSet;
import java.util.Set;
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
      @Nonnull final Set<String> literals, @Nonnull final String clazz, final @Nonnull byte[] classFile) {

    Set<Secret> secrets = getSecrets(literals, clazz);
    if (secrets != null) {
      if (!overheadController.consumeQuota(Operations.REPORT_VULNERABILITY, null)) {
        return;
      }
      reportVulnerability(secrets, clazz, classFile);
    }
  }

  private void reportVulnerability(
      final Set<Secret> secrets,
      final String clazz,
      final @Nonnull byte[] classFile) {
    ClassReader classReader = new ClassReader(classFile);
    IastClassVisitor classVisitor = new IastClassVisitor(secrets, clazz, reporter);
    classReader.accept(classVisitor, 0);
  }

  private Set<Secret> getSecrets(final Set<String> literals, final String clazz) {
    Set<Secret> secrets = null;
    for (String literal : literals) {
      if (literal.length() >= MIN_SECRET_LENGTH) {
        for (SecretMatcher secretMatcher : matchers) {
          if (secretMatcher.matches(literal)) {
            if(secrets == null){
              secrets = new HashSet<>();
            }
            secrets.add(new Secret(literal, secretMatcher.getRedactedEvidence()));
          }
        }
      }
    }
    return secrets;
  }

  public static class Secret{
    private final String value;
    private final String redacted;

    public Secret(final String value, final String redacted) {
      this.value = value;
      this.redacted = redacted;
    }

    public String getValue() {
      return value;
    }

    public String getRedacted() {
      return redacted;
    }

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
