package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.agent.tooling.stratum.StratumManager;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;

public class StratumListener implements Advices.Listener {

  public static final StratumListener INSTANCE = new StratumListener();

  private StratumListener() {
    // Prevent instantiation
  }

  @Override
  public void onConstantPool(
      @Nonnull TypeDescription type, @Nonnull ConstantPool pool, byte[] classFile) {
    if (StratumManager.shouldBeAnalyzed(type.getInternalName())) {
      StratumManager.INSTANCE.analyzeClass(classFile);
    }
  }
}
