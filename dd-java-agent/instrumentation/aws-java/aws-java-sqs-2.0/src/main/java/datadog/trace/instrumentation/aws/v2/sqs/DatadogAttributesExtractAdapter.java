package datadog.trace.instrumentation.aws.v2.sqs;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.messaging.DatadogAttributeParser;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class DatadogAttributesExtractAdapter
    implements AgentPropagation.ContextVisitor<Map<String, ?>> {

  public static final DatadogAttributesExtractAdapter GETTER =
      new DatadogAttributesExtractAdapter();

  @Override
  public void forEachKey(Map<String, ?> carrier, AgentPropagation.KeyClassifier classifier) {
    DatadogAttributeParser.forEachProperty(classifier, carrier);
  }
}
