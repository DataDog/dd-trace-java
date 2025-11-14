package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.SECONDS;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.ProviderEvent;
import dev.openfeature.sdk.ProviderEventDetails;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.OpenFeatureError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.lang.reflect.Constructor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Provider extends EventProvider implements Metadata {

  static final String METADATA = "datadog-openfeature-provider";
  private static final String EVALUATOR_IMPL =
      "datadog.trace.api.openfeature.DDFeatureFlaggingEvaluator";
  private static final Options DEFAULT_OPTIONS = new Options().initTimeout(30, SECONDS);
  private volatile Evaluator evaluator;
  private final Options options;
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  public Provider() {
    this(DEFAULT_OPTIONS, null);
  }

  public Provider(final Options options) {
    this(options, null);
  }

  Provider(final Options options, final Evaluator evaluator) {
    this.options = options;
    this.evaluator = evaluator;
  }

  @Override
  public void initialize(final EvaluationContext context) throws Exception {
    try {
      evaluator = buildEvaluator();
      final boolean init = evaluator.initialize(options.getTimeout(), options.getUnit(), context);
      initialized.set(init);
      if (!init) {
        throw new ProviderNotReadyError(
            "Provider timed-out while waiting for initial configuration");
      }
    } catch (final OpenFeatureError e) {
      throw e;
    } catch (final Throwable e) {
      throw new FatalError("Failed to initialize provider, is the tracer configured?", e);
    }
  }

  private void onConfigurationChange() {
    if (initialized.getAndSet(true)) {
      emit(
          ProviderEvent.PROVIDER_CONFIGURATION_CHANGED,
          ProviderEventDetails.builder().message("New configuration received").build());
    } else {
      emit(
          ProviderEvent.PROVIDER_READY,
          ProviderEventDetails.builder().message("Provider ready").build());
    }
  }

  private Evaluator buildEvaluator() throws Exception {
    if (evaluator != null) {
      return evaluator;
    }
    final Class<?> evaluatorClass = loadEvaluatorClass();
    final Constructor<?> ctor = evaluatorClass.getConstructor(Runnable.class);
    return (Evaluator) ctor.newInstance((Runnable) this::onConfigurationChange);
  }

  @Override
  public void shutdown() {
    if (evaluator != null) {
      evaluator.shutdown();
    }
  }

  @Override
  public Metadata getMetadata() {
    return this;
  }

  @Override
  public String getName() {
    return METADATA;
  }

  @Override
  public ProviderEvaluation<Boolean> getBooleanEvaluation(
      final String key, final Boolean defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Boolean.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      final String key, final String defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(String.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      final String key, final Integer defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Integer.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      final String key, final Double defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Double.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      final String key, final Value defaultValue, final EvaluationContext ctx) {
    return evaluator.evaluate(Value.class, key, defaultValue, ctx);
  }

  @SuppressForbidden // Class#forName(String) used to lazy load internal-api dependencies
  protected Class<?> loadEvaluatorClass() throws ClassNotFoundException {
    return Class.forName(EVALUATOR_IMPL);
  }

  public static class Options {

    private long timeout;
    private TimeUnit unit;

    public Options initTimeout(final long timeout, final TimeUnit unit) {
      this.timeout = timeout;
      this.unit = unit;
      return this;
    }

    public long getTimeout() {
      return timeout;
    }

    public TimeUnit getUnit() {
      return unit;
    }
  }
}
