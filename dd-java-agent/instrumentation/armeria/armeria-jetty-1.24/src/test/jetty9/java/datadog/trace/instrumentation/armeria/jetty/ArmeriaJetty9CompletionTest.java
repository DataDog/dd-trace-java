package datadog.trace.instrumentation.armeria.jetty;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.jetty.JettyService;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.utils.OkHttpUtils;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import okhttp3.Request;
import okhttp3.Response;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.junit.jupiter.api.Test;

class ArmeriaJetty9CompletionTest extends AbstractInstrumentationTest {
  @Test
  void finishesServletSpanWhenRequestLogThrows() throws Exception {
    CountDownLatch requestLogged = new CountDownLatch(1);
    org.eclipse.jetty.server.Server jetty = new org.eclipse.jetty.server.Server();
    jetty.setHandler(new DefaultHandler());
    jetty.setRequestLog((request, response) -> {
      requestLogged.countDown();
      throw new IllegalStateException("Request log failed");
    });

    Server armeria = Server.builder().http(0).serviceUnder("/", JettyService.of(jetty)).build();
    try {
      armeria.start().get(10, SECONDS);
      Request request =
          new Request.Builder().url("http://localhost:" + armeria.activeLocalPort() + "/").build();
      try (Response response = OkHttpUtils.client(10, 10, SECONDS).newCall(request).execute()) {
        assertEquals(404, response.code());
      }

      assertTrue(requestLogged.await(10, SECONDS), "Jetty did not reach the request log");
      assertTraces(
          trace(span().root().operationName(Pattern.compile("servlet\\.request")).type("web")));
    } finally {
      armeria.stop().get(10, SECONDS);
    }
  }
}
