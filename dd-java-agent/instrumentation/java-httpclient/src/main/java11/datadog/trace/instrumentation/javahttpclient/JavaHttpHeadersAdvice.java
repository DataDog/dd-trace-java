package datadog.trace.instrumentation.javahttpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpHeaders;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.javahttpclient.JavaHttpClientDecorator.HTTP_REQUEST;

public class JavaHttpHeadersAdvice {
  public static class HeadersAdvice {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeadersAdvice.class);

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static AgentScope methodExit(@Advice.Return(readOnly = false) HttpHeaders headers) {
      LOGGER.info("Entering HeadersAdvice methodExit");
      final AgentSpan span = startSpan(HTTP_REQUEST);
      final AgentScope scope = activateSpan(span);

      Map<String, List<String>> headerMap = new HashMap<>(headers.map());
      propagate().inject(span, headerMap, (carrier, key, value) -> carrier.put(key, List.of(value)));

      headers = HttpHeaders.of(headerMap, (k, v) -> true);

      return scope;
    }
  }
}
