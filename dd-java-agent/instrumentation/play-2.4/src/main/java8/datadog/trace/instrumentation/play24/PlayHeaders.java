package datadog.trace.instrumentation.play24;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import play.api.mvc.Headers;
import scala.Option;
import scala.collection.JavaConversions;

public class PlayHeaders extends CachingContextVisitor<Headers> {

  public static final PlayHeaders GETTER = new PlayHeaders();

  @Override
  public void forEachKey(
      Headers carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    for (String entry : JavaConversions.asJavaIterable(carrier.keys())) {
      String lowerCaseKey = toLowerCase(entry);
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        Option<String> value = carrier.get(entry);
        if (value.nonEmpty()) {
          if (!consumer.accept(classification, lowerCaseKey, value.get())) {
            return;
          }
        }
      }
    }
  }
}
