package datadog.trace.instrumentation.play26;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import play.api.mvc.Headers;

public class PlayHeaders implements AgentPropagation.ContextVisitor<Headers> {

  public static final PlayHeaders GETTER = new PlayHeaders();

  private static final MethodHandle AS_MAP = asMap();

  @Override
  public void forEachKey(Headers carrier, AgentPropagation.KeyClassifier classifier) {
    Map<String, List<String>> map = toMap(carrier);
    for (Map.Entry<String, List<String>> entry : map.entrySet()) {
      List<String> values = entry.getValue();
      if (!values.isEmpty() && !classifier.accept(entry.getKey(), values.get(0))) {
        return;
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> toMap(Headers headers) {
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
    }
    return null;
  }
}
