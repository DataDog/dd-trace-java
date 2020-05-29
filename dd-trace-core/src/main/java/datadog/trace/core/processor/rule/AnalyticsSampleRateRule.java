package datadog.trace.core.processor.rule;

import datadog.trace.api.DDTags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.Collection;
import java.util.Map;

/** Converts analytics sample rate tag to metric */
public class AnalyticsSampleRateRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {"AnalyticsSampleRateDecorator"};
  }

  @Override
  public void processSpan(
      final DDSpan span, final Map<String, Object> tags, final Collection<DDSpan> trace) {
    final Object sampleRateValue = span.getAndRemoveTag(DDTags.ANALYTICS_SAMPLE_RATE);
    if (sampleRateValue instanceof Number) {
      span.context().setMetric(DDTags.ANALYTICS_SAMPLE_RATE, (Number) sampleRateValue);
    } else if (sampleRateValue instanceof String) {
      try {
        span.context()
            .setMetric(DDTags.ANALYTICS_SAMPLE_RATE, Double.parseDouble((String) sampleRateValue));
      } catch (final NumberFormatException ex) {
        // ignore
      }
    }
  }
}
