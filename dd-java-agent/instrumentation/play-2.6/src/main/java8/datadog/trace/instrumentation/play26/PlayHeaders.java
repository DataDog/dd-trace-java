package datadog.trace.instrumentation.play26;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import play.api.mvc.Headers;
import scala.Tuple2;
import scala.collection.Iterator;
import scala.collection.Map;

public class PlayHeaders implements AgentPropagation.ContextVisitor<Headers> {

  public static final PlayHeaders GETTER = new PlayHeaders();

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, String> map = carrier.toSimpleMap();
    Iterator<Tuple2<String, String>> it = map.iterator();
    while (it.hasNext()) {
      Tuple2<String, String> entry = it.next();
      if (!classifier.accept(entry._1, entry._2)) {
        return;
      }
    }
  }
}
