package datadog.trace.instrumentation.httpclient;

import datadog.context.propagation.CarrierSetter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

public class HttpHeadersInjectAdapter implements CarrierSetter<Map<String, List<String>>> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();
  public static final BiPredicate<String, String> KEEP = HttpHeadersInjectAdapter::keep;

  @Override
  public void set(final Map<String, List<String>> carrier, final String key, final String value) {
    carrier.put(key, Collections.singletonList(value));
  }

  public static boolean keep(String key, String value) {
    return true;
  }
}
