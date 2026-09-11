package datadog.trace.agent.tooling.advice;

/** Ordered build-time consumer of a reusable {@link AdviceScanResult}. */
public interface AdviceProcessor<T> {
  /** Type used to publish this processor's output to later processors. */
  Class<T> resultType();

  T process(AdviceScanResult scanResult, AdviceProcessorContext context);
}
