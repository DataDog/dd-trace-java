package datadog.trace.instrumentation.play23;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import play.api.mvc.Headers;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.collection.Seq;

public class PlayHeaders implements AgentPropagation.ContextVisitor<Headers> {

  public static final PlayHeaders GETTER = new PlayHeaders();

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    for (Tuple2<String, Seq<String>> entry : JavaConversions.asJavaIterable(carrier.data())) {
      if (!classifier.accept(entry._1, entry._2.head())) {
        return;
      }
    }
  }
}
