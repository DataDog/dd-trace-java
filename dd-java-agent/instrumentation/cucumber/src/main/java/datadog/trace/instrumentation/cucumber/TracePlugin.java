package datadog.trace.instrumentation.cucumber;

import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.MutableSpan;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Step;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestStepFinished;
import io.cucumber.plugin.event.TestStepStarted;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

public class TracePlugin implements EventListener {

  private String tracingSpanType = "test";

  private Span scenarioSpan;
  private Scope scenarioScope;
  private Span stepSpan;
  private Scope stepScope;

  private void receiveTestCaseStarted(TestCaseStarted event) {
    Tracer tracer = GlobalTracer.get();
    scenarioSpan =
        tracer
            .buildSpan("scenario")
            .withTag(DDTags.ANALYTICS_SAMPLE_RATE, 1.0f)
            .withTag(DDTags.RESOURCE_NAME, event.getTestCase().getName())
            .withTag(DDTags.SPAN_TYPE, tracingSpanType)
            .withTag("span.kind", tracingSpanType)
            .withTag("test.framework", "io.cucumber")
            .withTag("test.type", "scenario")
            .withTag("test.name", "io.cucumber")
            .start();
    MutableSpan s = (MutableSpan) scenarioSpan;
    s.setResourceName(event.getTestCase().getName());
    s.setSpanType("test");
    scenarioScope = tracer.activateSpan(scenarioSpan);
  }

  private void receiveTestCaseFinished(TestCaseFinished event) {
    try {
      Result result = event.getResult();
      MutableSpan s = (MutableSpan) scenarioSpan;
      if (!result.getStatus().isOk()) {
        s.setError(true);
        s.setTag("test.status", "fail");
      } else {
        s.setTag("test.status", "pass");
      }
    } finally {
      scenarioScope.close();
      scenarioScope = null;
      scenarioSpan.finish();
      scenarioSpan = null;
    }
  }

  private void receiveTestStepStarted(TestStepStarted event) {
    if (event.getTestStep() instanceof PickleStepTestStep) {
      PickleStepTestStep ts = (PickleStepTestStep) event.getTestStep();
      Step step = ts.getStep();
      String name = step.getText();
      Tracer tracer = GlobalTracer.get();
      stepSpan =
          tracer
              .buildSpan("step")
              .asChildOf(scenarioSpan)
              .withTag(DDTags.RESOURCE_NAME, name)
              .withTag(DDTags.SPAN_TYPE, step.getKeyword())
              .withTag("span.kind", "step")
              .withTag("test.framework", "io.cucumber")
              .withTag("test.name", name)
              .start();
      MutableSpan ms = (MutableSpan) stepSpan;
      ms.setResourceName(name);
      ms.setSpanType(step.getKeyword());
      stepScope = tracer.activateSpan(scenarioSpan);
    }
  }

  private void receiveTestStepFinished(TestStepFinished event) {
    if (stepSpan != null) {
      try {
        Result result = event.getResult();
        if (!result.getStatus().isOk()) {
          MutableSpan s = (MutableSpan) stepSpan;
          s.setError(true);
        }
      } finally {
        stepScope.close();
        stepScope = null;
        stepSpan.finish();
        stepSpan = null;
      }
    }
  }

  @Override
  public void setEventPublisher(EventPublisher publisher) {
    publisher.registerHandlerFor(TestCaseStarted.class, this::receiveTestCaseStarted);
    publisher.registerHandlerFor(TestStepStarted.class, this::receiveTestStepStarted);
    publisher.registerHandlerFor(TestStepFinished.class, this::receiveTestStepFinished);
    publisher.registerHandlerFor(TestCaseFinished.class, this::receiveTestCaseFinished);
  }
}
