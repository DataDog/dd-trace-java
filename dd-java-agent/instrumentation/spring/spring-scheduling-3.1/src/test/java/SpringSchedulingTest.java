import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.scheduling.ScheduledTasksEndpoint;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SpringSchedulingTest extends AbstractInstrumentationTest {

  protected boolean legacyTracing() {
    return false;
  }

  @Test
  void scheduleTriggerTestAccordingToCronExpression() throws InterruptedException {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TriggerTaskConfig.class, SchedulingConfig.class);
    TriggerTask task = context.getBean(TriggerTask.class);
    ScheduledTasksEndpoint scheduledTaskEndpoint = context.getBean(ScheduledTasksEndpoint.class);

    task.blockUntilExecute();
    // Capture cron tasks before closing the context (endpoint is unavailable after close).
    Object cronTasks = scheduledTaskEndpoint.scheduledTasks().getCron();
    // Close the context immediately after the first execution to prevent a second cron
    // firing before assertions complete, which would produce extra traces and cause flakiness.
    context.close();

    assertNotNull(task);
    assertScheduledTraces("TriggerTask.run");
    assertNotNull(scheduledTaskEndpoint);
    assertNotNull(cronTasks);
  }

  @Test
  void scheduleIntervalTest() throws InterruptedException {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(IntervalTaskConfig.class, SchedulingConfig.class);
    try {
      IntervalTask task = context.getBean(IntervalTask.class);
      task.blockUntilExecute();

      assertNotNull(task);
      assertScheduledTraces("IntervalTask.run");
    } finally {
      context.close();
    }
  }

  @Test
  void scheduleLambdaTest() throws InterruptedException {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(LambdaTaskConfig.class, SchedulingConfig.class);
    try {
      LambdaTaskConfigurer configurer = context.getBean(LambdaTaskConfigurer.class);
      configurer.singleUseLatch.await(2000, TimeUnit.MILLISECONDS);

      assertScheduledTraces(name -> name.toString().contains("LambdaTaskConfigurer$$Lambda"));
    } finally {
      context.close();
    }
  }

  private void assertScheduledTraces(String resourceName) {
    assertScheduledTraces(name -> resourceName.contentEquals(name));
  }

  private void assertScheduledTraces(Predicate<CharSequence> resourceMatcher) {
    boolean hasParent = legacyTracing();
    if (hasParent) {
      assertTraces(
          trace(
              SORT_BY_START_TIME, parentSpan(), scheduledSpan(resourceMatcher).childOfPrevious()));
    } else {
      assertTraces(trace(parentSpan()), trace(scheduledSpan(resourceMatcher).root()));
    }
  }

  private static SpanMatcher parentSpan() {
    return span().resourceName(name -> "parent".contentEquals(name)).tags(defaultTags());
  }

  private static SpanMatcher scheduledSpan(Predicate<CharSequence> resourceMatcher) {
    return span()
        .resourceName(resourceMatcher)
        .operationName(Pattern.compile(Pattern.quote("scheduled.call")))
        .error(false)
        .tags(defaultTags(), tag(Tags.COMPONENT, is("spring-scheduling")));
  }
}

@WithConfig(key = "spring-scheduling.legacy.tracing.enabled", value = "true")
class SpringSchedulingLegacyTracingForkedTest extends SpringSchedulingTest {
  @Override
  protected boolean legacyTracing() {
    return true;
  }
}
