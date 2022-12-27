package datadog.trace.instrumentation.play26;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import play.api.mvc.Headers;
import scala.Tuple2;
import scala.collection.Iterator;

public final class PlayHeaders {

  public static final class Request implements AgentPropagation.ContextVisitor<Headers> {
    private static final MethodHandle AS_MAP = asMap();

    public static final Request GETTER = new Request();

    @Override
    public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, List<String>> entry : toMap(carrier).entrySet()) {
        List<String> values = entry.getValue();
        if (!values.isEmpty() && !classifier.accept(entry.getKey(), values.get(0))) {
          return;
        }
      }
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, List<String>> toMap(Headers headers) {
      if (null != AS_MAP) {
        try {
          return (Map<String, List<String>>) AS_MAP.invokeExact(headers);
        } catch (Throwable ignore) {
        }
      }
      return headers.asJava().toMap();
    }

    private static MethodHandle asMap() {
      try {
        // this is available in Play 2.8 and doesn't copy
        return MethodHandles.lookup()
            .findVirtual(Headers.class, "asMap", MethodType.methodType(Map.class));
      } catch (NoSuchMethodException | IllegalAccessException findFallback) {
        try {
          // this is available in Play 2.7 and doesn't copy
          return MethodHandles.lookup()
              .findVirtual(Headers.class, "toMap", MethodType.methodType(Map.class));
        } catch (NoSuchMethodException | IllegalAccessException giveup) {
        }
        return null;
      }
    }
  }

  public static final class Result implements AgentPropagation.ContextVisitor<play.api.mvc.Result> {
    public static final Result GETTER = new Result();

    @Override
    public void forEachKey(play.api.mvc.Result carrier, AgentPropagation.KeyClassifier classifier) {
      Iterator<Tuple2<String, String>> iterator = carrier.header().headers().iterator();
      while (iterator.hasNext()) {
        Tuple2<String, String> entry = iterator.next();
        if (!classifier.accept(entry._1, entry._2)) {
          return;
        }
      }
    }
  }
}
