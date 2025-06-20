package datadog.trace.instrumentation.resilience4j;

public abstract class FallbackAbstractInstrumentation extends AbstractResilience4jInstrumentation {

  public FallbackAbstractInstrumentation() {
    super("resilience4j-fallback");
  }
}
