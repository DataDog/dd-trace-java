package datadog.trace.instrumentation.junit4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.rules.RuleChain;
import org.junit.runner.notification.RunNotifier;

@AutoService(Instrumenter.class)
public class JUnit4Instrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public JUnit4Instrumentation() {
    super("junit", "junit-4");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.runner.notification.RunNotifier";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JUnit4Decorator",
      packageName + ".TracingListener",
      packageName + ".JUnit4Utils"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor(), JUnit4Instrumentation.class.getName() + "$JUnit4Advice");
  }

  public static class JUnit4Advice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void addTracingListener(
        @Advice.This(typing = Assigner.Typing.DYNAMIC) final RunNotifier runNotifier) {
      runNotifier.removeListener(TracingListener.INSTANCE);
      runNotifier.addListener(TracingListener.INSTANCE);
    }

    // JUnit 4.10 and above
    public static void muzzleCheck(final RuleChain ruleChain) {
      ruleChain.apply(null, null);
    }
  }
}
