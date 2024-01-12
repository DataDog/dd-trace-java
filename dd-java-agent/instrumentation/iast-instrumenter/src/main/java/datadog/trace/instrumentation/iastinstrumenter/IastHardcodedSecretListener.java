package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.api.Config;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.HardcodedSecretModule;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;

public class IastHardcodedSecretListener implements Advices.Listener {

  private static final int MIN_SECRET_LENGTH = 10;

  public static final IastHardcodedSecretListener INSTANCE = new IastHardcodedSecretListener();

  @Override
  public void onConstantPool(
      final @Nonnull TypeDescription type,
      final @Nonnull ConstantPool pool,
      final @Nonnull byte[] classFile) {
    if (!Config.get().isIastHardcodedSecretEnabled()) {
      return;
    }
    final HardcodedSecretModule iastModule = InstrumentationBridge.HARDCODED_SECRET;
    if (iastModule != null) {
      Set<String> literals = new HashSet<>();
      for (int index = 1; index < pool.getCount(); index++) {
        if (pool.getType(index) == ConstantPool.CONSTANT_STRING_TAG) {
          final int literalIndex = pool.readUnsignedShort(pool.getOffset(index));
          final String literal = pool.readUTF8(pool.getOffset(literalIndex));
          if (literal.length() >= MIN_SECRET_LENGTH) {
            literals.add(literal);
          }
        }
      }
      if (!literals.isEmpty()) {
        iastModule.onStringLiteral(literals, type.getName(), classFile);
      }
    }
  }
}
