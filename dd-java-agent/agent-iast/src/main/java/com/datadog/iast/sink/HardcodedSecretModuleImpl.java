package com.datadog.iast.sink;

import static com.datadog.iast.util.HardcodedSecretMatcher.HARDCODED_SECRET_MATCHERS;

import com.datadog.iast.Dependencies;
import com.datadog.iast.util.HardcodedSecretMatcher;
import com.datadog.iast.util.IastSecretVisitor;
import datadog.trace.api.iast.sink.HardcodedSecretModule;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;

public class HardcodedSecretModuleImpl extends SinkModuleBase implements HardcodedSecretModule {

  public HardcodedSecretModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onStringLiteral(
      @Nonnull final Set<String> literals,
      @Nonnull final String clazz,
      final @Nonnull byte[] classFile) {

    Map<String, String> secrets = getSecrets(literals);
    if (secrets != null) {
      reportVulnerability(secrets, clazz, classFile);
    }
  }

  private void reportVulnerability(
      final Map<String, String> secrets, final String clazz, final @Nonnull byte[] classFile) {
    ClassReader classReader = new ClassReader(classFile);
    IastSecretVisitor classVisitor = new IastSecretVisitor(secrets, clazz, reporter);
    classReader.accept(classVisitor, 0);
  }

  @Nullable
  private Map<String, String> getSecrets(final Set<String> literals) {
    Map<String, String> secrets = null;
    for (String literal : literals) {
      for (HardcodedSecretMatcher secretMatcher : HARDCODED_SECRET_MATCHERS) {
        if (secretMatcher.matches(literal)) {
          if (secrets == null) {
            secrets = new HashMap<>();
          }
          secrets.put(literal, secretMatcher.getRedactedEvidence());
          break;
        }
      }
    }
    return secrets;
  }
}
