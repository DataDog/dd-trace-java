package datadog.trace.instrumentation.httpclient;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.httpclient.HttpHeadersInjectAdapter.KEEP;
import static datadog.trace.instrumentation.httpclient.HttpHeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.httpclient.JavaNetClientDecorator.DECORATE;
import static java.lang.String.CASE_INSENSITIVE_ORDER;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.bytebuddy.asm.Advice;

public class HeadersAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(@Advice.Return(readOnly = false) HttpHeaders headers) {
    // Note: adding duplicate keys will throw an IllegalArgumentException so we need to dedupe
    // case insensitively
    final Map<String, List<String>> headerMap = new TreeMap<>(CASE_INSENSITIVE_ORDER);
    headerMap.putAll(headers.map());
    DECORATE.injectContext(getCurrentContext(), headerMap, SETTER);
    headers = HttpHeaders.of(headerMap, KEEP);
  }
}
