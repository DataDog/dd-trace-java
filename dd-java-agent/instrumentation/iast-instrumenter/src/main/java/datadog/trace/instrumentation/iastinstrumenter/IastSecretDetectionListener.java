package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.SecretsModule;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;

public class IastSecretDetectionListener implements Advices.Listener {

  public static final IastSecretDetectionListener INSTANCE = new IastSecretDetectionListener();

  @Override
  public void onConstantPool(
      final @Nonnull TypeDescription type, final @Nonnull ConstantPool pool) {
    final SecretsModule iastModule = InstrumentationBridge.HARDCODED_SECRET;
    if (iastModule != null) {
      for (int index = 1; index < pool.getCount(); index++) {
        if (pool.getType(index) == ConstantPool.CONSTANT_STRING_TAG) {
          final int literalIndex = pool.readUnsignedShort(pool.getOffset(index));
          final String literal = pool.readUTF8(pool.getOffset(literalIndex));
          iastModule.onStringLiteral(literal, type.getName());
        }
      }
    }
  }
}
