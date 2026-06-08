import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Verifies that when an {@code @Async} method throws an exception, the span produced by the
 * spring-scheduling instrumentation captures the error and the associated error tags.
 */
class SpringAsyncErrorTest extends AbstractInstrumentationTest {

  @Test
  void asyncMethodErrorIsCaptured() {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(AsyncErrorTaskConfig.class);
    try {
      AsyncErrorTask asyncErrorTask = context.getBean(AsyncErrorTask.class);

      // The exception thrown inside the @Async method propagates back through the returned future.
      assertThrows(Throwable.class, () -> asyncErrorTask.asyncThrow().join());

      assertTraces(
          trace(
              span()
                  .resourceName(name -> "AsyncErrorTask.asyncThrow".contentEquals(name))
                  .error(true)
                  .tags(
                      defaultTags(),
                      error(IllegalStateException.class, AsyncErrorTask.ERROR_MESSAGE))));
    } finally {
      context.close();
    }
  }
}
