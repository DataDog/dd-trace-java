package datadog.trace.instrumentation.weaver;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Captures declaration tags before Weaver converts test names into outcome strings. */
@AutoService(InstrumenterModule.class)
public class WeaverTestTagsInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public WeaverTestTagsInstrumentation() {
    super("ci-visibility", "weaver");
  }

  @Override
  public String hierarchyMarkerType() {
    return "weaver.RunnableSuite";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("spec").and(takesArguments(1)),
        WeaverTestTagsInstrumentation.class.getName() + "$CaptureTagsAdvice");
  }

  public static class CaptureTagsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void capture(@Advice.This Object suite) {
      DatadogWeaverReporter.registerTestTags(suite);
    }
  }
}
