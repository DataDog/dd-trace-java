package datadog.trace.instrumentation.httpclient;

import datadog.context.propagation.CarrierSetter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

public class HttpHeadersInjectAdapter
    implements CarrierSetter<Map<CaseInsensitiveKey, List<String>>> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();
  public static final BiPredicate<String, String> KEEP = HttpHeadersInjectAdapter::keep;

  public static boolean keep(String key, String value) {
    return true;
  }

  @Override
  public void set(Map<CaseInsensitiveKey, List<String>> carrier, String key, String value) {
    carrier.put(new CaseInsensitiveKey(key), Collections.singletonList(value));
  }
}
