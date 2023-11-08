package datadog.smoketest.springboot;

import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ssrf")
public class SsrfController {

  @PostMapping("/execute")
  public String apacheSsrf(
      @RequestParam("url") final String url, @RequestParam("method") final String method) {
    try {
      HttpClient client = new DefaultHttpClient();
      execute(client, url, ExecuteMethod.valueOf(method));
    } catch (Exception e) {
    }
    return "OK";
  }

  private void execute(final HttpClient client, final String url, ExecuteMethod executeMethod)
      throws IOException {
    HttpUriRequest request = new HttpGet(url);
    HttpHost host =
        new HttpHost(
            request.getURI().getHost(), request.getURI().getPort(), request.getURI().getScheme());
    switch (executeMethod) {
      case REQUEST:
        client.execute(request);
        break;
      case REQUEST_CONTEXT:
        client.execute(request, new BasicHttpContext());
        break;
      case HOST_REQUEST:
        client.execute(host, request);
        break;
      case REQUEST_HANDLER:
        client.execute(request, new BasicResponseHandler());
        break;
      case REQUEST_HANDLER_CONTEXT:
        client.execute(request, new BasicResponseHandler(), new BasicHttpContext());
        break;
      case HOST_REQUEST_HANDLER:
        client.execute(host, request, new BasicResponseHandler());
        break;
      case HOST_REQUEST_HANDLER_CONTEXT:
        client.execute(host, request, new BasicResponseHandler(), new BasicHttpContext());
        break;
      case HOST_REQUEST_CONTEXT:
        client.execute(host, request, new BasicHttpContext());
        break;
      default:
        break;
    }
  }

  public enum ExecuteMethod {
    REQUEST,
    REQUEST_CONTEXT,
    HOST_REQUEST,
    REQUEST_HANDLER,
    REQUEST_HANDLER_CONTEXT,
    HOST_REQUEST_HANDLER,
    HOST_REQUEST_HANDLER_CONTEXT,
    HOST_REQUEST_CONTEXT
  }
}
