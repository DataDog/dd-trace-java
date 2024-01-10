package com.datadog.iast.sink;

import static com.datadog.iast.util.HardcodedSecretMatcher.HARDCODED_SECRET_MATCHERS;

import com.datadog.iast.Dependencies;
import com.datadog.iast.util.HardcodedSecretMatcher;
import com.datadog.iast.util.IastClassVisitor;
import datadog.trace.api.iast.sink.HardcodedSecretModule;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;

public class HardcodedSecretModuleImpl extends SinkModuleBase implements HardcodedSecretModule {

  private static final int MIN_SECRET_LENGTH = 12;

  public HardcodedSecretModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onStringLiteral(
      @Nonnull final Set<String> literals,
      @Nonnull final String clazz,
      final @Nonnull byte[] classFile) {

    Set<Secret> secrets = getSecrets(literals, clazz);
    if (secrets != null) {
      reportVulnerability(secrets, clazz, classFile);
    }
  }

  private void reportVulnerability(
      final Set<Secret> secrets, final String clazz, final @Nonnull byte[] classFile) {
    ClassReader classReader = new ClassReader(classFile);
    IastClassVisitor classVisitor = new IastClassVisitor(secrets, clazz, reporter);
    classReader.accept(classVisitor, 0);
  }

  @Nullable
  private Set<Secret> getSecrets(final Set<String> literals, final String clazz) {
    Set<Secret> secrets = null;
    for (String literal : literals) {
      if (literal.length() >= MIN_SECRET_LENGTH) {
        for (HardcodedSecretMatcher secretMatcher : HARDCODED_SECRET_MATCHERS) {
          if (secretMatcher.matches(literal)) {
            if (secrets == null) {
              secrets = new HashSet<>();
            }
            secrets.add(new Secret(literal, secretMatcher.getRedactedEvidence()));
          }
        }
      }
    }
    return secrets;
  }

  public static class Secret {
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
}
