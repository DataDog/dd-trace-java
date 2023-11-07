package server;

import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

  @PostMapping("/apache_ssrf")
  public String apacheSsrf(
      @RequestParam("url") final String url, @RequestParam("method") int method) {
    try {
      HttpClient client = new DefaultHttpClient();
      execute(client, url, method);
    } catch (Exception e) {
    }
    return "OK";
  }

  private void execute(final HttpClient client, final String url, int method) throws IOException {
    HttpUriRequest request = new HttpGet(url);
    HttpHost host =
        new HttpHost(
            request.getURI().getHost(), request.getURI().getPort(), request.getURI().getScheme());
    switch (method) {
      case 1:
        client.execute(request);
        break;
      case 2:
        client.execute(request, new BasicHttpContext());
        break;
      case 3:
        client.execute(host, request);
        break;
      case 4:
        client.execute(request, new BasicResponseHandler());
        break;
      case 5:
        client.execute(request, new BasicResponseHandler(), new BasicHttpContext());
        break;
      case 6:
        client.execute(host, request, new BasicResponseHandler());
        break;
      case 7:
        client.execute(host, request, new BasicResponseHandler(), new BasicHttpContext());
        break;
      default:
        client.execute(host, request, new BasicHttpContext());
        break;
    }
  }
}
