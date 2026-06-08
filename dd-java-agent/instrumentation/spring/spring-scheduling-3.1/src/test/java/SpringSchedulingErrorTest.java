import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Verifies that when a {@code @Scheduled} task throws an exception, the span produced by the
 * spring-scheduling instrumentation captures the error and the associated error tags.
 */
class SpringSchedulingErrorTest extends AbstractInstrumentationTest {

  @Test
  void scheduledTaskErrorIsCaptured() throws InterruptedException {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(ScheduledErrorTaskConfig.class);
    try {
      ScheduledErrorTask task = context.getBean(ScheduledErrorTask.class);
      assertTrue(task.blockUntilExecute(), "scheduled task did not execute");
      // Stop the scheduler so the periodic task cannot fire a second time and produce extra traces.
      context.close();

      assertTraces(
          trace(
              span()
                  .resourceName(name -> "ScheduledErrorTask.run".contentEquals(name))
                  .error(true)
                  .tags(
                      defaultTags(),
                      tag(Tags.COMPONENT, is("spring-scheduling")),
                      error(IllegalStateException.class, ScheduledErrorTask.ERROR_MESSAGE))));
    } finally {
      context.close();
    }
  }
}
