package opentelemetry14.context.propagation;

import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class TextMap
    implements TextMapGetter<Map<String, String>>, TextMapSetter<Map<String, String>> {
  public static final TextMap INSTANCE = new TextMap();

  @Override
  public Iterable<String> keys(Map<String, String> carrier) {
    return carrier.keySet();
  }

  @Override
  @Nullable
  public String get(@Nullable Map<String, String> carrier, String key) {
    return carrier == null ? null : carrier.get(key);
  }

  @Override
  public void set(@Nullable Map<String, String> carrier, String key, String value) {
    if (carrier != null) {
      carrier.put(key, value);
    }
  }
}
