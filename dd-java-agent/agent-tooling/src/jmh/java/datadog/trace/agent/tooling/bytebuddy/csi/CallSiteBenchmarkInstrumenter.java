package datadog.trace.agent.tooling.bytebuddy.csi;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSiteAdvice.HasHelpers;
import datadog.trace.agent.tooling.csi.InvokeAdvice;
import datadog.trace.agent.tooling.csi.Pointcut;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;

public class CallSiteBenchmarkInstrumenter extends CallSiteInstrumenter
    implements ElementMatcher<TypeDescription> {

  public CallSiteBenchmarkInstrumenter() {
    super(buildCallSites(), "call-site");
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
    return this;
  }

  @Override
  public boolean matches(final TypeDescription target) {
    return CallSiteTrie.apply(target.getName()) != 1;
  }

  @Override
  public boolean isEnabled() {
    return "callSite".equals(System.getProperty("dd.benchmark.instrumentation", ""));
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return true;
  }

  private static List<CallSiteAdvice> buildCallSites() {
    return Collections.<CallSiteAdvice>singletonList(new GetParameterCallSite());
  }

  private static class GetParameterCallSite implements InvokeAdvice, HasHelpers, Pointcut {

    @Override
    public Pointcut pointcut() {
      return this;
    }

    @Override
    public void apply(
        final MethodHandler handler,
        final int opcode,
        final String owner,
        final String name,
        final String descriptor,
        final boolean isInterface) {
      handler.method(
          Opcodes.INVOKESTATIC,
          "datadog/trace/agent/tooling/bytebuddy/csi/CallSiteBenchmarkHelper",
          "adviceCallSite",
          "(Ljavax/servlet/ServletRequest;Ljava/lang/String;)Ljava/lang/String;",
          false);
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {CallSiteBenchmarkHelper.class.getName()};
    }

    @Override
    public String type() {
      return "javax/servlet/ServletRequest";
    }

    @Override
    public String method() {
      return "getParameter";
    }

    @Override
    public String descriptor() {
      return "(Ljava/lang/String;)Ljava/lang/String;";
    }
  }

  public static class Muzzle extends ReferenceMatcher {}
}
