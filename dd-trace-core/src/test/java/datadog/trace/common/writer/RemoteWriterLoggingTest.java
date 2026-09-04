package datadog.trace.common.writer;

import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT;
import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.DROPPED_BUFFER_OVERFLOW_SINGLE_SPAN;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Buffer overflow is only newsworthy when a trace the tracer meant to send was lost. Overflow that
 * discards an already sampled-out trace is routine and must not reach the user as a warning.
 */
class RemoteWriterLoggingTest extends DDCoreJavaSpecification {

  private Logger logger;
  private Level previousLevel;
  private ListAppender<ILoggingEvent> appender;

  private final HealthMetrics monitor = mock(HealthMetrics.class);
  private final TraceProcessingWorker worker = mock(TraceProcessingWorker.class);
  private final PayloadDispatcherImpl dispatcher = mock(PayloadDispatcherImpl.class);
  private final DDAgentWriter writer =
      new DDAgentWriter(worker, dispatcher, monitor, 1, SECONDS, false);

  @BeforeEach
  void attachAppender() {
    logger = (Logger) LoggerFactory.getLogger(RemoteWriter.class);
    previousLevel = logger.getLevel();
    // WARN, not DEBUG: at DEBUG the writer logs the detailed message instead of the rate-limited
    // warning, which is not the path a user in production sees.
    logger.setLevel(Level.WARN);
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    logger.detachAppender(appender);
    logger.setLevel(previousLevel);
    writer.close();
  }

  @Test
  void warnsWhenAKeptTraceIsLostToOverflow() {
    write(PrioritySampling.SAMPLER_KEEP, DROPPED_BUFFER_OVERFLOW);

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertTrue(
        event.getFormattedMessage().contains("kept trace"),
        "the warning should say a kept trace was lost: " + event.getFormattedMessage());
  }

  @Test
  void staysQuietWhenOnlyASampledOutTraceIsLostToOverflow() {
    write(PrioritySampling.SAMPLER_DROP, DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT);

    assertEquals(
        Collections.emptyList(),
        appender.list,
        "losing an already sampled-out trace must not warn the user");
  }

  @Test
  void warnsWhenASingleSpanSamplingCandidateIsLostToOverflow() {
    write(PrioritySampling.SAMPLER_DROP, DROPPED_BUFFER_OVERFLOW_SINGLE_SPAN);

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertTrue(
        event.getFormattedMessage().contains("single span sampling"),
        "the warning should say a single span sampling candidate was lost: "
            + event.getFormattedMessage());
  }

  /**
   * The rate limiter holds a single budget, so a flood of the benign case must not consume the
   * budget that the genuine warning needs.
   */
  @Test
  void sampledOutOverflowDoesNotSuppressAKeptTraceWarning() {
    for (int i = 0; i < 100; i++) {
      write(PrioritySampling.SAMPLER_DROP, DROPPED_BUFFER_OVERFLOW_SAMPLED_OUT);
    }

    write(PrioritySampling.SAMPLER_KEEP, DROPPED_BUFFER_OVERFLOW);

    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    // The surviving warning must be the one about the kept trace, not a benign one that happened
    // to claim the budget first.
    assertTrue(
        event.getFormattedMessage().contains("kept trace"),
        "expected the kept-trace warning, got: " + event.getFormattedMessage());
  }

  private void write(byte priority, PublishResult result) {
    DDSpan root = buildSpan(0L, "test.tag", "test.value", PropagationTags.factory().empty());
    root.setSamplingPriority(priority);
    List<DDSpan> trace = Collections.singletonList(root);
    when(worker.publish(any(), anyInt(), eq(trace))).thenReturn(result);

    writer.write(trace);
  }
}
