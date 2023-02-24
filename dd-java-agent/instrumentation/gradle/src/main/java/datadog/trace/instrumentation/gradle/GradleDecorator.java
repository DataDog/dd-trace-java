package datadog.trace.instrumentation.gradle;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.TestDecorator;

public class GradleDecorator extends TestDecorator {
  public static final GradleDecorator DECORATE = new GradleDecorator();

  @Override
  public String testFramework() {
    return "gradle";
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"gradle-build-listener"};
  }

  @Override
  public String component() {
    return "gradle";
  }

  public void afterTestSessionStart(final AgentSpan span) {
    span.setSpanType(InternalSpanTypes.TEST_SESSION_END);
    span.setTag(Tags.SPAN_KIND, testSessionSpanKind());

    span.setResourceName(
        "gradle.test_session"); // FIXME "resource": "mocha.test_session.yarn test", ????

    // FIXME
    // test.command
    // test.framework
    // test.framework_version
    // test_session.code_coverage.enabled
    // test_session.itr.tests_skipping.enabled
    // _dd.ci.itr.tests_skipped

    afterStart(span);
  }

  public void beforeTestSessionFinish(final AgentSpan span) {
    span.setTag(Tags.TEST_STATUS, "pass"); // FIXME
    span.setTag(Tags.TEST_SESSION_ID, span.getSpanId());

    beforeFinish(span);
  }
}
