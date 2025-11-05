package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.SECONDS;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventProvider;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import dev.openfeature.sdk.exceptions.FatalError;
import dev.openfeature.sdk.exceptions.OpenFeatureError;
import dev.openfeature.sdk.exceptions.ProviderNotReadyError;
import java.util.concurrent.TimeUnit;

public class Provider extends EventProvider implements Metadata {

  static final String METADATA = "datadog-openfeature-provider";
  static final Options DEFAULT_OPTIONS = new Options().initTimeout(30, SECONDS);
  static final String INITIALIZER_CLASS = "datadog.trace.api.openfeature.DDProviderInitializer";

  private final Options options;
  private final Initializer initializer;

  public Provider() {
    this(DEFAULT_OPTIONS);
  }

  public Provider(final Options options) {
    this(options, Initializer.withReflection(INITIALIZER_CLASS));
  }

  Provider(final Options options, final Initializer initializer) {
    this.options = options;
    this.initializer = initializer;
  }

  @Override
  public void initialize(final EvaluationContext context) throws Exception {
    try {
      if (!initializer.init(this, options.getTimeout(), options.getUnit())) {
        throw new ProviderNotReadyError(
            "Provider timed-out while waiting for initial configuration");
      }
    } catch (final OpenFeatureError e) {
      throw e;
    } catch (final Throwable e) {
      throw new FatalError(
          "Failed to initialize provider, is the tracer enabled with -javaagent?", e);
    }
  }

  @Override
  public void shutdown() {
    initializer.close();
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
    return initializer.evaluator().evaluate(Boolean.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<String> getStringEvaluation(
      final String key, final String defaultValue, final EvaluationContext ctx) {
    return initializer.evaluator().evaluate(String.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Integer> getIntegerEvaluation(
      final String key, final Integer defaultValue, final EvaluationContext ctx) {
    return initializer.evaluator().evaluate(Integer.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Double> getDoubleEvaluation(
      final String key, final Double defaultValue, final EvaluationContext ctx) {
    return initializer.evaluator().evaluate(Double.class, key, defaultValue, ctx);
  }

  @Override
  public ProviderEvaluation<Value> getObjectEvaluation(
      final String key, final Value defaultValue, final EvaluationContext ctx) {
    return initializer.evaluator().evaluate(Value.class, key, defaultValue, ctx);
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
