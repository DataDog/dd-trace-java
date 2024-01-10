package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.HardcodedSecretModule;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;

public class IastHardcodedSecretListener implements Advices.Listener {

  public static final IastHardcodedSecretListener INSTANCE = new IastHardcodedSecretListener();

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
          final String literal = pool.readUTF8(pool.getOffset(literalIndex));
          literals.add(literal);
        }
      }
      if (!literals.isEmpty()) {
        iastModule.onStringLiteral(literals, type.getName(), classFile);
      }
    }
  }
}
