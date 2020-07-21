package datadog.trace.instrumentation.play24;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import play.api.mvc.Headers;
import scala.Option;
import scala.collection.JavaConversions;

public class PlayHeaders implements AgentPropagation.ContextVisitor<Headers> {

  public static final PlayHeaders GETTER = new PlayHeaders();

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    for (String entry : JavaConversions.asJavaIterable(carrier.keys())) {
      Option<String> value = carrier.get(entry);
      if (value.nonEmpty() && !classifier.accept(entry, value.get())) {
        return;
      }
    }
  }
}
