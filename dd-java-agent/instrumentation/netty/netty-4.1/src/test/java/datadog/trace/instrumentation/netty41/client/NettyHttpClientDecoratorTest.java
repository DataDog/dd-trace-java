package datadog.trace.instrumentation.netty41.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import java.net.URI;
import org.tabletest.junit.TableTest;

class NettyHttpClientDecoratorTest {

  @TableTest({
    "Scenario                        | Method  | Uri                       | Host             | Expected                    ",
    "absolute uri                    | GET     | 'http://example.com/path' |                  | 'http://example.com/path'   ",
    "relative uri with host header   | GET     | '/path'                   | 'example.com:80' | 'http://example.com:80/path'",
    "unparsable uri                  | GET     | '/bad path'               |                  |                             ",
    "unparsable uri with host header | GET     | '/bad path'               | 'example.com'    |                             ",
    "connect                         | CONNECT | 'example.com:443'         |                  | 'http://example.com:443'    ",
    "unparsable connect              | CONNECT | 'bad host:443'            |                  |                             "
  })
  void urlOfRequest(String method, String uri, String host, String expected) throws Exception {
    // Mocked rather than a real DefaultHttpRequest: newer netty versions validate the request
    // line eagerly in that constructor and reject the unparsable-URI fixtures before the
    // decorator under test ever sees them.
    HttpRequest request = mock(HttpRequest.class);
    when(request.method()).thenReturn(HttpMethod.valueOf(method));
    when(request.uri()).thenReturn(uri);
    HttpHeaders headers = new DefaultHttpHeaders();
    if (host != null) {
      headers.set("Host", host);
    }
    when(request.headers()).thenReturn(headers);

    URI url = NettyHttpClientDecorator.DECORATE.url(request);

    assertEquals(expected, url == null ? null : url.toString());
  }
}
