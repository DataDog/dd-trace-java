package datadog.trace.instrumentation.weaver;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Captures declaration tags with the task identity used by Weaver's reporting events. */
@AutoService(InstrumenterModule.class)
public class WeaverTestTagsInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public WeaverTestTagsInstrumentation() {
    super("ci-visibility", "weaver");
  }

  @Override
  public String instrumentedType() {
    return "weaver.framework.RunnerCompat$IOTask";
  }

  @Override
  public ElementMatcher<? super MethodDescription> methodIgnoreMatcher() {
    return none();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // The suite-loaded flatMap callback receives the actual suite returned by its provider.
    transformer.applyAdvice(
        takesArguments(2).and(takesArgument(1, named("weaver.EffectSuite"))),
        WeaverTestTagsInstrumentation.class.getName() + "$CaptureTagsAdvice");
  }

  public static class CaptureTagsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void capture(
        @Advice.FieldValue("fqn") String suiteName, @Advice.Argument(1) Object suite) {
      DatadogWeaverReporter.registerTestTags(suiteName, suite);
    }
  }
}
