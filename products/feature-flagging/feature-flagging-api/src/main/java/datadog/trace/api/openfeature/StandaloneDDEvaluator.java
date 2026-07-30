package datadog.trace.api.openfeature;

import datadog.openfeature.internal.core.ConfigurationSnapshot;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.concurrent.TimeUnit;

/** No-agent entrypoint that owns CDN polling and delegates evaluation to the shared core. */
final class StandaloneDDEvaluator implements Evaluator {

  private final Runnable configCallback;
  private final OpenFeatureEvaluationAdapter evaluator =
      new OpenFeatureEvaluationAdapter(null, false);
  private volatile StandaloneProviderRuntime.Handle runtime;

  StandaloneDDEvaluator(final Runnable configCallback) {
    this.configCallback = configCallback;
  }

  @Override
  public boolean initialize(
      final long timeout, final TimeUnit unit, final EvaluationContext context) throws Exception {
    StandaloneProviderRuntime.Handle current = runtime;
    if (current == null) {
      synchronized (this) {
        current = runtime;
        if (current == null) {
          current =
              StandaloneProviderRuntime.acquire(
                  StandaloneRuntimeConfiguration.resolve(), ignored -> configCallback.run());
          runtime = current;
        }
      }
    }
    return current.awaitConfiguration(timeout, unit) || hasConfiguration();
  }

  @Override
  public boolean hasConfiguration() {
    final StandaloneProviderRuntime.Handle current = runtime;
    return current != null && current.configuration() != null;
  }

  @Override
  public void shutdown() {
    final StandaloneProviderRuntime.Handle current = runtime;
    runtime = null;
    if (current != null) {
      current.close();
    }
  }

  @Override
  public <T> ProviderEvaluation<T> evaluate(
      final Class<T> target,
      final String key,
      final T defaultValue,
      final EvaluationContext context) {
    final StandaloneProviderRuntime.Handle current = runtime;
    final ConfigurationSnapshot snapshot = current == null ? null : current.configuration();
    return evaluator.evaluate(snapshot, target, key, defaultValue, context);
  }
}
