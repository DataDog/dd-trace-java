package datadog.trace.instrumentation.jms;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.FixedSizeCache;
import java.util.Enumeration;
import javax.jms.JMSException;
import javax.jms.Message;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MessageExtractAdapter implements AgentPropagation.ContextVisitor<Message> {

  private static final FixedSizeCache.Creator<String, String> KEY_MAPPER =
      new FixedSizeCache.Creator<String, String>() {
        @Override
        public String create(String key) {
          return key.replace('$', '-')
              // true story \/
              .replace("__dash__", "-")
              .toLowerCase();
        }
      };

  private final FixedSizeCache<String, String> cache = new FixedSizeCache<>(32);

  public static final MessageExtractAdapter GETTER = new MessageExtractAdapter();

  @Override
  public void forEachKey(
      Message carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    try {
      final Enumeration<?> enumeration = carrier.getPropertyNames();
      if (null != enumeration) {
        while (enumeration.hasMoreElements()) {
          String key = ((String) enumeration.nextElement());
          String lowerCaseKey = cache.computeIfAbsent(key, KEY_MAPPER);
          int classification = classifier.classify(lowerCaseKey);
          if (classification != IGNORE) {
            Object value = carrier.getObjectProperty(key);
            if (!consumer.accept(classification, lowerCaseKey, (String) value)) {
              return;
            }
          }
        }
      }
    } catch (JMSException e) {
      throw new RuntimeException(e);
    }
  }
}
