package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.agent.tooling.iast.stratum.Stratum;
import datadog.trace.agent.tooling.iast.stratum.StratumManager;
import datadog.trace.api.Pair;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.bytebuddy.description.type.TypeDescription;

public class IastJSPClassListener implements Advices.Listener {

  public static final IastJSPClassListener INSTANCE = new IastJSPClassListener();

  @Override
  public void onConstantPool(
      @Nonnull TypeDescription type, @Nonnull ConstantPool pool, byte[] classFile) {
    if (StratumManager.shouldBeAnalyzed(type.getInternalName())) {
      StratumManager.INSTANCE.analyzeClass(classFile);
    }
  }

  @Nullable
  public Pair<String, Integer> getFileAndLine(final String clazz, final int line) {
    Stratum stratum = StratumManager.INSTANCE.get(clazz);
    if (stratum != null) {
      return Pair.of(stratum.getSourceFile(), stratum.getInputLineNumber(line));
    }
    return null;
  }
}
