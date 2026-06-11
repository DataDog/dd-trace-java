package datadog.trace.instrumentation.gax;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import com.google.api.core.ApiClock;
import com.google.api.core.NanoClock;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.retrying.BasicResultRetryAlgorithm;
import com.google.api.gax.retrying.ExponentialRetryAlgorithm;
import com.google.api.gax.retrying.RetryAlgorithm;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.retrying.RetryingFuture;
import com.google.api.gax.retrying.ScheduledRetryingExecutor;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.threeten.bp.Duration;

class GaxRetryContinuationTest extends AbstractInstrumentationTest {

  @Test
  void supersededAttemptListenerDoesNotLeak() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ScheduledRetryingExecutor<String> executor =
          new ScheduledRetryingExecutor<>(retryAlgorithm(), scheduler);
      RetryingFuture<String> retryingFuture = executor.createFuture(() -> "ok");

      AgentSpan span = startSpan("gax", "publish");
      try (AgentScope scope = activateSpan(span)) {
        // reserve the attempt slot with a placeholder that is never completed
        SettableApiFuture<String> placeholder = SettableApiFuture.create();
        retryingFuture.setAttemptFuture(placeholder);
        // supersede it with the real attempt future -> must cancel the placeholder's continuation
        SettableApiFuture<String> attempt = SettableApiFuture.create();
        retryingFuture.setAttemptFuture(attempt);
        // real attempt completes -> its listener runs -> resolves normally
        attempt.set("ok");
      }
      span.finish();
      // expect one trace that will be dropped if this instrumentation is not working
      writer.waitForTraces(1);
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static RetryAlgorithm<String> retryAlgorithm() {
    RetrySettings settings =
        RetrySettings.newBuilder()
            .setMaxAttempts(1)
            .setInitialRetryDelay(Duration.ofMillis(1))
            .setRetryDelayMultiplier(1.0)
            .setMaxRetryDelay(Duration.ofMillis(1))
            .setInitialRpcTimeout(Duration.ofMillis(10))
            .setRpcTimeoutMultiplier(1.0)
            .setMaxRpcTimeout(Duration.ofMillis(10))
            .setTotalTimeout(Duration.ofMillis(10))
            .build();
    ApiClock clock = NanoClock.getDefaultClock();
    return new RetryAlgorithm<>(
        new BasicResultRetryAlgorithm<>(), new ExponentialRetryAlgorithm(settings, clock));
  }
}
