package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.agent.tooling.iast.stratum.StratumManagerImpl;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;

public class StratumListener implements Advices.Listener {

  public static final StratumListener INSTANCE = new StratumListener();

  @Override
  public void onConstantPool(
      @Nonnull TypeDescription type, @Nonnull ConstantPool pool, byte[] classFile) {
    if (StratumManagerImpl.shouldBeAnalyzed(type.getInternalName())) {
      StratumManagerImpl.INSTANCE.analyzeClass(classFile);
    }
  }
}
