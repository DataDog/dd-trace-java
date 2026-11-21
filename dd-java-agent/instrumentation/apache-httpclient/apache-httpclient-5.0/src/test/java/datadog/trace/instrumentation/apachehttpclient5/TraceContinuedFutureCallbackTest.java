package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.instrumentation.apachehttpclient5.TraceContinuedFutureCallback.extractHttpResponse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.junit.jupiter.api.Test;

class TraceContinuedFutureCallbackTest {

  @Test
  void resultResponseWinsOverSharedContextResponse() {
    HttpResponse own = new BasicHttpResponse(200);
    // a context shared across concurrent requests can hold another exchange's response
    HttpContext shared = contextWith(new BasicHttpResponse(503));

    assertSame(own, extractHttpResponse(own, shared));
  }

  @Test
  void messageResultHeadWinsOverSharedContextResponse() {
    HttpResponse own = new BasicHttpResponse(200);
    HttpContext shared = contextWith(new BasicHttpResponse(503));

    assertSame(own, extractHttpResponse(new Message<>(own, "body"), shared));
  }

  @Test
  void contextResponseUsedWhenResultCarriesNoResponse() {
    HttpResponse fromContext = new BasicHttpResponse(200);
    HttpContext context = contextWith(fromContext);

    assertSame(fromContext, extractHttpResponse("body", context));
  }

  @Test
  void contextResponseUsedWhenThereIsNoResult() {
    // failed() and cancelled() have no result
    HttpResponse fromContext = new BasicHttpResponse(200);

    assertSame(fromContext, extractHttpResponse(null, contextWith(fromContext)));
  }

  @Test
  void noResponseWhenNeitherResultNorContextCarriesOne() {
    assertNull(extractHttpResponse("body", new BasicHttpContext()));
    assertNull(extractHttpResponse(null, null));
  }

  private static HttpContext contextWith(HttpResponse response) {
    HttpContext context = new BasicHttpContext();
    context.setAttribute(HttpCoreContext.HTTP_RESPONSE, response);
    return context;
  }
}
