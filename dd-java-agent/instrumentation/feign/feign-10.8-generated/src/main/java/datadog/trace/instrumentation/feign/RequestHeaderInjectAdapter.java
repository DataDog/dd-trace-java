package datadog.trace.instrumentation.feign;

import datadog.context.propagation.CarrierSetter;
import feign.Request;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class RequestHeaderInjectAdapter implements CarrierSetter<Map<String, Collection<String>>> {

  public static final RequestHeaderInjectAdapter SETTER = new RequestHeaderInjectAdapter();

  @Override
  public void set(
      final Map<String, Collection<String>> carrier, final String key, final String value) {
    Collection<String> values = new ArrayList<>(1);
    values.add(value);
    carrier.put(key, values);
  }

  /**
   * Feign Request objects are immutable — headers cannot be modified after creation. This method
   * creates a new Request with the trace context headers injected.
   */
  public static Request inject(
      final Request original, final Map<String, Collection<String>> injectedHeaders) {
    Map<String, Collection<String>> merged = new LinkedHashMap<>(original.headers());
    merged.putAll(injectedHeaders);
    return Request.create(
        original.httpMethod(), original.url(), merged, original.body(), StandardCharsets.UTF_8);
  }
}
