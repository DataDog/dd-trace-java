package datadog.trace.instrumentation.opentracing31;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import io.opentracing.propagation.TextMap;
import java.util.Map;

class OTPropagation {

  static class TextMapInjectSetter implements AgentPropagation.Setter<TextMap> {
    static final TextMapInjectSetter INSTANCE = new TextMapInjectSetter();

    @Override
    public void set(final TextMap carrier, final String key, final String value) {
      carrier.put(key, value);
    }
  }

  static class TextMapExtractGetter extends CachingContextVisitor<TextMap> {

    static final TextMapExtractGetter INSTANCE = new TextMapExtractGetter();

    private TextMapExtractGetter() {}

    @Override
    public void forEachKey(
        TextMap carrier,
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
