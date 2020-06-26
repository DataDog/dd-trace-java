package datadog.trace.instrumentation.opentracing32;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import io.opentracing.propagation.TextMapExtract;
import io.opentracing.propagation.TextMapInject;
import java.util.Map;

class OTPropagation {

  static class TextMapInjectSetter implements AgentPropagation.Setter<TextMapInject> {
    static final TextMapInjectSetter INSTANCE = new TextMapInjectSetter();

    @Override
    public void set(final TextMapInject carrier, final String key, final String value) {
      carrier.put(key, value);
    }
  }

  static class TextMapExtractGetter extends CachingContextVisitor<TextMapExtract> {
    static final TextMapExtractGetter INSTANCE = new TextMapExtractGetter();

    private TextMapExtractGetter() {}

    @Override
    public void forEachKey(
        TextMapExtract carrier,
        AgentPropagation.KeyClassifier classifier,
        AgentPropagation.KeyValueConsumer consumer) {
      // This is the same as the one passed into the constructor
      // So using "extracted" is valid
      for (Map.Entry<String, String> entry : carrier) {
        String lowerCaseKey = toLowerCase(entry.getKey());
        int classification = classifier.classify(lowerCaseKey);
        if (classification != IGNORE) {
          if (!consumer.accept(classification, lowerCaseKey, entry.getValue())) {
            return;
          }
        }
      }
    }
  }
}
