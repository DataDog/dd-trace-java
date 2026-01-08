package datadog.trace.instrumentation.httpclient;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.httpclient.HttpHeadersInjectAdapter.KEEP;
import static datadog.trace.instrumentation.httpclient.HttpHeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE;

import java.net.http.HttpHeaders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

public class HeadersAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(@Advice.Return(readOnly = false) HttpHeaders headers) {
    final Map<CaseInsensitiveKey, List<String>> headerMap = new LinkedHashMap<>();
    // Note: we don't want to modify the case of the current headers
    // However adding duplicate keys will throw an IllegalArgumentException so we need to dedupe
    // case insensitively
    for (final Map.Entry<String, List<String>> entry : headers.map().entrySet()) {
      headerMap.put(new CaseInsensitiveKey(entry.getKey()), entry.getValue());
    }
    DECORATE.injectContext(getCurrentContext(), headerMap, SETTER);
    // convert back
    final Map<String, List<String>> finalMap = new LinkedHashMap<>();
    for (final Map.Entry<CaseInsensitiveKey, List<String>> entry : headerMap.entrySet()) {
      finalMap.put(entry.getKey().getValue(), entry.getValue());
    }
    headers = HttpHeaders.of(finalMap, KEEP);
  }
}
