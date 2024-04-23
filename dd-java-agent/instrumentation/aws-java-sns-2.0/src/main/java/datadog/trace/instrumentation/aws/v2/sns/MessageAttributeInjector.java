package datadog.trace.instrumentation.aws.v2.sns;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

public class MessageAttributeInjector
    implements AgentPropagation.Setter<Map<String, MessageAttributeValue>> {

  public static final MessageAttributeInjector SETTER = new MessageAttributeInjector();

  @Override
  public void set(
      final Map<String, MessageAttributeValue> carrier, final String key, final String value) {
    // The injector would use "X-Amzn-Trace-Id" key, but to keep the behavior same as SQS, use
    // "AWSTraceHeader"
    // Also checks if the key already exists because AWS could in the future automatically injects
    // AWSTraceHeader
    // (as it does today in SQS)
    System.out.println("[JOEY]2");

    if (Objects.equals(key, "X-Amzn-Trace-Id")
        && carrier.size() < 10
        && !carrier.containsKey("AWSTraceHeader")) {
      System.out.println("[JOEY]3");
      System.out.println(value);
      carrier.put(
          "AWSTraceHeader",
          MessageAttributeValue.builder().dataType("String").stringValue(value).build());
    }
  }
}
