package datadog.trace.agent.tooling;

/** Carries advice and target context for a failed advice transformation. */
final class AdviceTransformationException extends RuntimeException {
  private final String instrumentationClass;
  private final String adviceClass;
  private final String targetClass;
  private final String targetMethod;

  AdviceTransformationException(
      String instrumentationClass,
      String adviceClass,
      String targetClass,
      String targetMethod,
      Throwable cause) {
    super("Advice transformation failed for " + targetClass + '.' + targetMethod, cause);
    this.instrumentationClass = instrumentationClass;
    this.adviceClass = adviceClass;
    this.targetClass = targetClass;
    this.targetMethod = targetMethod;
  }

  String getInstrumentationClass() {
    return instrumentationClass;
  }

  String getAdviceClass() {
    return adviceClass;
  }

  String getTargetClass() {
    return targetClass;
  }

  String getTargetMethod() {
    return targetMethod;
  }
}
