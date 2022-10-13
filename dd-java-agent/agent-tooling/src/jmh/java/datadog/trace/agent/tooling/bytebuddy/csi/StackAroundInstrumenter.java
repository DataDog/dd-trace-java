package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import net.bytebuddy.jar.asm.Opcodes;

public class StackAroundInstrumenter extends CallSiteTypeBaseInstrumenter {

  public StackAroundInstrumenter() {
    super("aroundStack", new AroundCallSite());
  }

  public static class AroundCallSite extends BaseCallSiteAdvice {

    @Override
    public void apply(
        MethodHandler handler,
        int opcode,
        String owner,
        String name,
        String descriptor,
        boolean isInterface) {
      handler.method(
          Opcodes.INVOKESTATIC,
          "datadog/trace/agent/tooling/bytebuddy/csi/CallSiteTypeBenchmarkHelper",
          "around",
          "(Ljava/lang/StringBuilder;I[C)Ljava/lang/StringBuilder;",
          false);
    }
  }

  public static class Muzzle extends ReferenceMatcher {}
}
