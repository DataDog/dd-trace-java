package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.hasInterfaceNamed;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import ratpack.func.Block;
import ratpack.path.PathBinding;

import java.util.Map;

@AutoService(Instrumenter.class)
public final class ContinuationInstrumentation extends Instrumenter.Default {

  public ContinuationInstrumentation() {
    super("ratpack");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return nameStartsWith("ratpack.exec.")
        .<TypeDescription>and(hasInterfaceNamed("ratpack.exec.internal.Continuation"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BlockWrapper",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        named("resume").and(takesArgument(0, named("ratpack.func.Block"))),
        ContinuationInstrumentation.class.getName() + "$ResumeAdvice");
  }

  public static class ResumeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrap(@Advice.Argument(value = 0, readOnly = false) Block block) {
      block = BlockWrapper.wrapIfNeeded(block, activeSpan());
    }

    public void muzzleCheck(final PathBinding binding) {
      // This was added in 1.4.  Added here to ensure consistency with other instrumentation.
      binding.getDescription();
    }
  }
}
