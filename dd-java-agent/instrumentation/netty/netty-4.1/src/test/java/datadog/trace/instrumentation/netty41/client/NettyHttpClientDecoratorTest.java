package datadog.trace.instrumentation.netty41.client;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.handler.codec.http.DefaultHttpRequest;
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
    HttpRequest request = new DefaultHttpRequest(HTTP_1_1, HttpMethod.valueOf(method), uri);
    if (host != null) {
      request.headers().set("Host", host);
    }

    URI url = NettyHttpClientDecorator.DECORATE.url(request);

    assertEquals(expected, url == null ? null : url.toString());
  }
}
