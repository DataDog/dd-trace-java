package datadog.trace.instrumentation.cucumber;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.cucumber.core.backend.StepDefinition;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class CucumberInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

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
    public static AgentScope onCucumberStepStart(
        @Advice.This StepDefinition step, @Advice.Argument(0) Object[] arguments) {
      return CucumberStepDecorator.DECORATE.onStepStart(step, arguments);
    }

    @Advice.OnMethodExit
    public static void onCucumberStepFinish(@Advice.Enter AgentScope scope) {
      CucumberStepDecorator.DECORATE.onStepFinish(scope);
    }

    // Cucumber 5.0.0 and above
    public static void muzzleCheck(io.cucumber.core.backend.StepDefinition stepDefinition) {
      stepDefinition.execute(null);
    }
  }
}
