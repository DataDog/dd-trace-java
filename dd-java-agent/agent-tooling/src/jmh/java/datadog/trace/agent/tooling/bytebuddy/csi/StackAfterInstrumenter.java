package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import net.bytebuddy.jar.asm.Opcodes;

public class StackAfterInstrumenter extends CallSiteTypeBaseInstrumenter {

  public StackAfterInstrumenter() {
    super("afterStack", new AfterCallSite());
  }

  public static class AfterCallSite extends BaseCallSiteAdvice implements CallSiteAdvice.HasFlags {

    @Override
    public int flags() {
      return COMPUTE_MAX_STACK;
    }

    @Override
    public void apply(
        MethodHandler handler,
        int opcode,
        String owner,
        String name,
        String descriptor,
        boolean isInterface) {
      handler.dupInvoke(owner, descriptor, StackDupMode.COPY);
      handler.method(opcode, owner, name, descriptor, isInterface);
      handler.method(
          Opcodes.INVOKESTATIC,
          "datadog/trace/agent/tooling/bytebuddy/csi/CallSiteTypeBenchmarkHelper",
          "after",
          "(Ljava/lang/StringBuilder;I[CLjava/lang/StringBuilder;)Ljava/lang/StringBuilder;",
          false);
    }
  }

  public static class Muzzle extends ReferenceMatcher {}
}
