package datadog.trace.instrumentation.cucumber;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import io.cucumber.core.backend.StepDefinition;
import java.util.Arrays;

public class CucumberStepDecorator extends BaseDecorator {

  public static CucumberStepDecorator DECORATE = new CucumberStepDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"cucumber", "cucumber-5"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return "cucumber";
  }

  public AgentScope onStepStart(StepDefinition step, Object[] arguments) {
    AgentSpan span = AgentTracer.startSpan("cucumber", "cucumber.step");
    afterStart(span);

    span.setResourceName(step.getPattern());
    span.setTag("step.name", step.getPattern());
    span.setTag("step.location", step.getLocation());
    span.setTag("step.type", step.getClass().getName());
    if (arguments != null && arguments.length > 0) {
      span.setTag("step.arguments", Arrays.toString(arguments));
    }

    return AgentTracer.activateSpan(span);
  }

  public void onStepFinish(AgentScope scope) {
    AgentSpan span = scope.span();
    beforeFinish(span);
    span.finish();
    scope.close();
  }
}
