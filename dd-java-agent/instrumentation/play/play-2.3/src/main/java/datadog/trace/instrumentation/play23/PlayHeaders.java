package datadog.trace.instrumentation.play23;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import play.api.mvc.Headers;
import scala.Tuple2;
import scala.collection.JavaConversions;
import scala.collection.Seq;

public abstract class PlayHeaders {

  public static final class Request implements AgentPropagation.ContextVisitor<Headers> {
    public static final Request GETTER = new Request();

    @Override
    public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
      for (Tuple2<String, Seq<String>> entry : JavaConversions.asJavaIterable(carrier.data())) {
        if (!classifier.accept(entry._1, entry._2.head())) {
          return;
        }
      }
    }
  }

  public static final class Result implements AgentPropagation.ContextVisitor<play.api.mvc.Result> {
    public static final Result GETTER = new Result();

    @Override
    public void forEachKey(play.api.mvc.Result carrier, AgentPropagation.KeyClassifier classifier) {
      for (Tuple2<String, String> entry :
          JavaConversions.asJavaIterable(carrier.header().headers())) {
        if (!classifier.accept(entry._1, entry._2)) {
          return;
        }
      }
    }
  }
}
