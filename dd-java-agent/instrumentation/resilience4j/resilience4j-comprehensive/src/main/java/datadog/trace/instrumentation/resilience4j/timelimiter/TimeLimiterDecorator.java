package datadog.trace.instrumentation.resilience4j.timelimiter;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.resilience4j.common.Resilience4jSpanDecorator;
import io.github.resilience4j.timelimiter.TimeLimiter;

public final class TimeLimiterDecorator extends Resilience4jSpanDecorator<TimeLimiter> {
  public static final TimeLimiterDecorator DECORATE = new TimeLimiterDecorator();
  public static final String TAG_PREFIX = "resilience4j.time_limiter.";

  private TimeLimiterDecorator() {
    super();
  }

  @Override
  public void decorate(AgentSpan span, TimeLimiter data) {
    span.setTag(TAG_PREFIX + "name", data.getName());
    span.setTag(TAG_PREFIX + "timeout_duration_ms",
        data.getTimeLimiterConfig().getTimeoutDuration().toMillis());
  }
}
