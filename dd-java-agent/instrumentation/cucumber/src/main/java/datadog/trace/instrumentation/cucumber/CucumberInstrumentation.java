package datadog.trace.instrumentation.cucumber;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.cucumber.core.backend.StepDefinition;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class CucumberInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public CucumberInstrumentation() {
    super("cucumber", "cucumber-5");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.cucumber.core.backend.StepDefinition";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()))
        .and(namedNoneOf("io.cucumber.core.runner.CoreStepDefinition"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".CucumberStepDecorator"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("execute").and(takesArguments(Object[].class)),
        CucumberInstrumentation.class.getName() + "$CucumberAdvice");
  }

  public static class CucumberAdvice {
    @Advice.OnMethodEnter
    public static void onCucumberStepStart(
        @Advice.This StepDefinition step, @Advice.Argument(0) Object[] arguments) {
      CucumberStepDecorator.DECORATE.onStepStart(step, arguments);
    }

    @Advice.OnMethodExit
    public static void onCucumberStepFinish(@Advice.This StepDefinition step) {
      CucumberStepDecorator.DECORATE.onStepFinish(step);
    }

    // Cucumber 5.0.0 and above
    public static void muzzleCheck(io.cucumber.core.backend.StepDefinition stepDefinition) {
      stepDefinition.execute(null);
    }
  }
}
