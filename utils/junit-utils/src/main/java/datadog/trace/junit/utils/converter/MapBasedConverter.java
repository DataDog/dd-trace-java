package datadog.trace.junit.utils.converter;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConverter;

public final class MapBasedConverter {

  @Nullable
  static Map<? super Object, ? super Object> handleMap(
      Object source, ParameterContext context, ArgumentConverter converter) {
    if (source instanceof Map) {
      // convert keys and values from the map
      Map<Object, Object> map = new HashMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) source).entrySet()) {
        map.put(converter.convert(e.getKey(), context), converter.convert(e.getValue(), context));
      }
      return map;
    }
    return null;
  }
}
