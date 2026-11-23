package datadog.trace.agent.tooling.advice;

/** Ordered build-time consumer of a reusable {@link AdviceScanResult}. */
public interface AdviceProcessor {
  void process(AdviceScanResult scanResult, AdviceProcessorContext context);
}
