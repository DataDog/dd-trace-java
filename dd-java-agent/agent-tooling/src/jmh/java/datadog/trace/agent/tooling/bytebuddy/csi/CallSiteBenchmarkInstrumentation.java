package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.AROUND;

import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.agent.tooling.csi.InvokeAdvice;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.util.Collections;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;

public class CallSiteBenchmarkInstrumentation extends CallSiteInstrumentation {

  public CallSiteBenchmarkInstrumentation() {
    super("call-site");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return CallSiteMatcher.INSTANCE;
  }

  @Override
  public boolean isEnabled() {
    return "callSite".equals(System.getProperty("dd.benchmark.instrumentation", ""));
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return true;
  }

  @Override
  protected CallSiteSupplier callSites() {
    return BenchmarkCallSites.INSTANCE;
  }

  public static class BenchmarkCallSites implements CallSiteSupplier {

    public static final CallSiteSupplier INSTANCE = new BenchmarkCallSites();

    @Override
    public Iterable<CallSites> get() {
      return Collections.singletonList(
          (container -> {
            container.addAdvice(
                AROUND,
                "javax/servlet/ServletRequest",
                "getParameter",
                "(Ljava/lang/String;)Ljava/lang/String;",
                getParameterAdvice());
            container.addHelpers(
                "datadog.trace.agent.tooling.bytebuddy.csi.CallSiteBenchmarkHelper");
          }));
    }

    public InvokeAdvice getParameterAdvice() {
      return (handler, opcode, owner, name, descriptor, isInterface) ->
          handler.method(
              Opcodes.INVOKESTATIC,
              "datadog/trace/agent/tooling/bytebuddy/csi/CallSiteBenchmarkHelper",
              "adviceCallSite",
              "(Ljavax/servlet/ServletRequest;Ljava/lang/String;)Ljava/lang/String;",
              false);
    }
  }

  public static final class CallSiteMatcher
      extends ElementMatcher.Junction.ForNonNullValues<TypeDescription> {
    public static final CallSiteMatcher INSTANCE = new CallSiteMatcher();

    @Override
    protected boolean doMatch(TypeDescription target) {
      return CallSiteTrie.apply(target.getName()) != 1;
    }
  }

  public static class Muzzle extends ReferenceMatcher {}
}
