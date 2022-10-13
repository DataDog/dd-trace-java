package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import net.bytebuddy.jar.asm.Opcodes;

public class StackBeforeInstrumenter extends CallSiteTypeBaseInstrumenter {

  public StackBeforeInstrumenter() {
    super("beforeStack", new BeforeCallSite());
  }

  public static class BeforeCallSite extends BaseCallSiteAdvice implements CallSiteAdvice.HasFlags {

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
      handler.method(
          Opcodes.INVOKESTATIC,
          "datadog/trace/agent/tooling/bytebuddy/csi/CallSiteTypeBenchmarkHelper",
          "before",
          "(Ljava/lang/StringBuilder;I[C)V",
          false);
      handler.method(opcode, owner, name, descriptor, isInterface);
    }
  }

  public static class Muzzle extends ReferenceMatcher {}
}
