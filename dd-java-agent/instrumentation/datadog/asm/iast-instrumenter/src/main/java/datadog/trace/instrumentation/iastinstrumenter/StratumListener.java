package datadog.trace.instrumentation.iastinstrumenter;

import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool;
import datadog.trace.agent.tooling.stratum.StratumManager;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;

public class StratumListener implements Advices.Listener {

  private final StratumManager stratumManager;

  public StratumListener(StratumManager stratumManager) {
    this.stratumManager = stratumManager;
  }

  @Override
  public void onConstantPool(
      @Nonnull TypeDescription type, @Nonnull ConstantPool pool, byte[] classFile) {
    if (shouldBeAnalyzed(type.getInternalName())) {
      stratumManager.analyzeClass(classFile);
    }
  }

  private static boolean shouldBeAnalyzed(final String internalClassName) {
    return internalClassName.contains("jsp")
        && (internalClassName.contains("_jsp")
            || internalClassName.contains("jsp_")
            || internalClassName.contains("_tag"));
  }
}
