package datadog.smoketest.springboot;

import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.AutoRetryHttpClient;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.ContentEncodingHttpClient;
import org.apache.http.impl.client.DecompressingHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.client.SystemDefaultHttpClient;
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
      @RequestParam("url") final String url,
      @RequestParam("client") final String client,
      @RequestParam("method") final String method) {
    try {
      HttpClient httpClient = getHttpClient(Client.valueOf(client));
      execute(httpClient, url, ExecuteMethod.valueOf(method));
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
        throw new IllegalArgumentException("Unknown execute method: " + executeMethod);
    }
  }

  private HttpClient getHttpClient(final Client client) {
    switch (client) {
      case DefaultHttpClient:
        return new DefaultHttpClient();
      case AutoRetryHttpClient:
        return new AutoRetryHttpClient();
      case ContentEncodingHttpClient:
        return new ContentEncodingHttpClient();
      case DecompressingHttpClient:
        return new DecompressingHttpClient();
      case InternalHttpClient:
        return HttpClientBuilder.create().build();
      case MinimalHttpClient:
        return HttpClients.createMinimal();
      case SystemDefaultHttpClient:
        return new SystemDefaultHttpClient();
      default:
        throw new IllegalArgumentException("Unknown client: " + client);
    }
  }

  public enum Client {
    DefaultHttpClient,
    AutoRetryHttpClient,
    ContentEncodingHttpClient,
    DecompressingHttpClient,
    InternalHttpClient,
    MinimalHttpClient,
    SystemDefaultHttpClient
  }

  private static enum ExecuteMethod {
    REQUEST(47),
    REQUEST_CONTEXT(50),
    HOST_REQUEST(53),
    REQUEST_HANDLER(56),
    REQUEST_HANDLER_CONTEXT(59),
    HOST_REQUEST_HANDLER(62),
    HOST_REQUEST_HANDLER_CONTEXT(65),
    HOST_REQUEST_CONTEXT(68);

    final Integer expectedLine;

    ExecuteMethod(Integer expectedLine) {
      this.expectedLine = expectedLine;
    }
  }
}
