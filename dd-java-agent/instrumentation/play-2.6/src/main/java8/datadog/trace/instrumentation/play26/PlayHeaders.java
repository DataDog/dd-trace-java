package datadog.trace.instrumentation.play26;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.CachingContextVisitor;
import play.api.mvc.Headers;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.Map;

public class PlayHeaders extends CachingContextVisitor<Headers> {

  public static final PlayHeaders GETTER = new PlayHeaders();

  @Override
  public void forEachKey(
      Headers carrier,
      AgentPropagation.KeyClassifier classifier,
      AgentPropagation.KeyValueConsumer consumer) {
    Map<String, String> map = carrier.toSimpleMap();
    Iterator<Tuple2<String, String>> it = map.iterator();
    while (it.hasNext()) {
      Tuple2<String, String> entry = it.next();
      String lowerCaseKey = toLowerCase(entry._1());
      int classification = classifier.classify(lowerCaseKey);
      if (classification != IGNORE) {
        if (!consumer.accept(classification, lowerCaseKey, entry._2())) {
          return;
        }
      }
    }
  }
}
