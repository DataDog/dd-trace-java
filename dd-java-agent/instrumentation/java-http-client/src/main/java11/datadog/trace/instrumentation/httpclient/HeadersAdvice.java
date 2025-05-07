package datadog.trace.instrumentation.httpclient;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.httpclient.HttpHeadersInjectAdapter.KEEP;
import static datadog.trace.instrumentation.httpclient.HttpHeadersInjectAdapter.SETTER;

import java.net.http.HttpHeaders;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.asm.Advice;

public class HeadersAdvice {
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(@Advice.Return(readOnly = false) HttpHeaders headers) {
    final Map<String, List<String>> headerMap = new HashMap<>(headers.map());
    defaultPropagator().inject(getCurrentContext(), headerMap, SETTER);
    headers = HttpHeaders.of(headerMap, KEEP);
  }
}
