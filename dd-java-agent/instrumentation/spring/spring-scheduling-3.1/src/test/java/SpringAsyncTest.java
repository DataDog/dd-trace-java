import static datadog.trace.agent.test.assertions.Matchers.validates;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.DDTags.THREAD_NAME;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class SpringAsyncTest extends AbstractInstrumentationTest {

  @ParameterizedTest(name = "context propagated through @async annotation, hasParent={0}")
  @ValueSource(booleans = {true, false})
  void contextPropagatedThroughAsyncAnnotation(boolean hasParent) {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(AsyncTaskConfig.class);
    try {
      AsyncTask asyncTask = context.getBean(AsyncTask.class);
      if (hasParent) {
        AgentSpan root = startSpan("test", "root");
        try (AgentScope scope = activateSpan(root)) {
          asyncTask.async().join();
        } finally {
          root.finish();
        }
        assertTraces(
            trace(
                SORT_BY_START_TIME,
                span().resourceName(name -> "root".contentEquals(name)).root(),
                asyncSpan("AsyncTask.async").childOfPrevious(),
                getIntSpan().childOfPrevious()));
      } else {
        asyncTask.async().join();
        assertTraces(
            trace(
                SORT_BY_START_TIME,
                asyncSpan("AsyncTask.async").root(),
                getIntSpan().childOfPrevious()));
      }
    } finally {
      context.close();
    }
  }

  private static SpanMatcher asyncSpan(String resourceName) {
    return span()
        .resourceName(name -> resourceName.contentEquals(name))
        .tags(defaultTags(), tag(THREAD_NAME, validates(SpringAsyncTest::isAsyncThread)));
  }

  private static SpanMatcher getIntSpan() {
    return span()
        .resourceName(name -> "AsyncTask.getInt".contentEquals(name))
        .tags(
            defaultTags(),
            tag(
                Tags.COMPONENT,
                validates(component -> "trace".contentEquals((CharSequence) component))),
            tag(THREAD_NAME, validates(SpringAsyncTest::isAsyncThread)));
  }

  private static boolean isAsyncThread(Object threadName) {
    return threadName != null && threadName.toString().startsWith("SimpleAsyncTaskExecutor");
  }
}
