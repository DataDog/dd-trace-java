package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.google.common.net.HostAndPort;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import ratpack.func.Block;
import ratpack.path.PathBinding;

@AutoService(Instrumenter.class)
public final class ContinuationInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public ContinuationInstrumentation() {
    super("ratpack");
  }

  @Override
  public String hierarchyMarkerType() {
    return "ratpack.exec.internal.Continuation";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("ratpack.exec.").and(implementsInterface(named(hierarchyMarkerType())));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BlockWrapper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("resume").and(takesArgument(0, named("ratpack.func.Block"))),
        ContinuationInstrumentation.class.getName() + "$ResumeAdvice");
  }

  public static class ResumeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Block block) {
      block = BlockWrapper.wrapIfNeeded(block, activeSpan());
    }

    public void muzzleCheck(final PathBinding binding, final HostAndPort host) {
      // This was added in 1.4.  Added here to ensure consistency with other instrumentation.
      binding.getDescription();

      // This is available in Guava 20 which was required starting in 1.5
      host.getHost();
    }
  }
}
