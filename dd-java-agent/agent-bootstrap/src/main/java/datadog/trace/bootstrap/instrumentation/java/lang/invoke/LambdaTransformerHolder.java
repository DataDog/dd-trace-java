package datadog.trace.bootstrap.instrumentation.java.lang.invoke;

/**
 * Holds the {@link LambdaTransformer} registered by the agent installer. Lives on the bootstrap
 * class path so it is reachable from instrumented {@code java.lang.invoke} code.
 */
public final class LambdaTransformerHolder {
  private static volatile LambdaTransformer transformer;

  private LambdaTransformerHolder() {}

  public static void set(LambdaTransformer transformer) {
    LambdaTransformerHolder.transformer = transformer;
  }

  public static LambdaTransformer get() {
    return transformer;
  }
}
