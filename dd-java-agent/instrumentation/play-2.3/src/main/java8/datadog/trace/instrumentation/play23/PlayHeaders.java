package datadog.trace.instrumentation.play23;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import play.api.mvc.Headers;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.collection.Seq;

public class PlayHeaders extends CachingContextVisitor<Headers> {

  public static final PlayHeaders GETTER = new PlayHeaders();

  @Override
  public void forEachKey(
      Headers carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    for (Tuple2<String, Seq<String>> entry : JavaConversions.asJavaIterable(carrier.data())) {
      String lowerCaseKey = toLowerCase(entry._1());
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE && !entry._2().isEmpty()) {
        if (!consumer.accept(classification, lowerCaseKey, entry._2().head())) {
          return;
        }
      }
    }
  }
}
