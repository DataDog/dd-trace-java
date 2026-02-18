package datadog.trace.instrumentation.iastinstrumenter;

import static datadog.trace.api.iast.secrets.HardcodedSecretMatcher.HARDCODED_SECRET_MATCHERS;
import static datadog.trace.api.iast.secrets.HardcodedSecretMatcher.MIN_SECRET_LENGTH;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.agent.tooling.iast.IastSecretClassReader;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.secrets.HardcodedSecretMatcher;
import datadog.trace.api.iast.sink.HardcodedSecretModule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.bytebuddy.description.type.TypeDescription;

public class IastHardcodedSecretListener implements Advices.Listener {

  public static final IastHardcodedSecretListener INSTANCE =
      new IastHardcodedSecretListener(IastSecretClassReader.INSTANCE);

  private final IastSecretClassReader iastSecretClassReader;

  protected IastHardcodedSecretListener(final IastSecretClassReader iastSecretClassReader) {
    this.iastSecretClassReader = iastSecretClassReader;
  }

  @Override
  public void onConstantPool(
      final @Nonnull TypeDescription type,
      final @Nonnull ConstantPool pool,
      final @Nonnull byte[] classFile) {
    final HardcodedSecretModule iastModule = InstrumentationBridge.HARDCODED_SECRET;
    if (iastModule != null) {
      Set<String> literals = new HashSet<>();
      for (int index = 1; index < pool.getCount(); index++) {
        if (pool.getType(index) == ConstantPool.CONSTANT_STRING_TAG) {
          final int literalIndex = pool.readUnsignedShort(pool.getOffset(index));
          int bytesLength = pool.readUnsignedShort(pool.getOffset(literalIndex));
          if (bytesLength >= MIN_SECRET_LENGTH) { // prefilter short strings
            final String literal = pool.readUTF8(pool.getOffset(literalIndex));
            if (literal.length() >= MIN_SECRET_LENGTH) {
              literals.add(literal);
            }
          }
        }
      }
      if (!literals.isEmpty()) {
        onStringLiteral(iastModule, literals, type.getName(), classFile);
      }
    }
  }

  private void onStringLiteral(
      final HardcodedSecretModule module,
      final Set<String> literals,
      final String clazz,
      final byte[] classFile) {
    Map<String, String> secrets = getSecrets(literals);
    if (secrets != null) {
      iastSecretClassReader.readClass(secrets, classFile, new ReportSecretConsumer(module, clazz));
    }
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

  private static class ReportSecretConsumer implements TriConsumer<String, String, Integer> {

    private final String clazz;

    private final HardcodedSecretModule module;

    public ReportSecretConsumer(final HardcodedSecretModule module, final String clazz) {
      this.module = module;
      this.clazz = clazz;
    }

    @Override
    public void accept(String method, String value, Integer currentLine) {
      module.onHardcodedSecret(value, method, clazz, currentLine);
    }
  }
}
