import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;

public class SsrfController {

  public String apacheSsrf(
      final String url,
      final String client,
      final String method,
      final String requestType,
      final String scheme) {
    try {
      HttpClient httpClient = getHttpClient(Client.valueOf(client));
      execute(httpClient, url, ExecuteMethod.valueOf(method), requestType, scheme);
    } catch (Exception e) {
    }
    return "OK";
  }

  private void execute(
      final HttpClient client,
      final String url,
      ExecuteMethod executeMethod,
      String requestType,
      String scheme)
      throws IOException {
    HttpUriRequest httpUriRequest = new HttpGet(url);
    boolean isUriRequest = requestType.equals(Request.HttpUriRequest.name());
    HttpHost host =
        new HttpHost(httpUriRequest.getURI().getHost(), httpUriRequest.getURI().getPort(), scheme);
    HttpRequest httpRequest =
        isUriRequest
            ? httpUriRequest
            : new BasicHttpRequest(
                "GET", url.startsWith(scheme) ? url : url.substring(host.toURI().length()));
    switch (executeMethod) {
      case REQUEST:
        client.execute(httpUriRequest);
        break;
      case REQUEST_CONTEXT:
        client.execute(httpUriRequest, new BasicHttpContext());
        break;
      case HOST_REQUEST:
        client.execute(host, httpRequest);
        break;
      case REQUEST_HANDLER:
        client.execute(httpUriRequest, new BasicResponseHandler());
        break;
      case REQUEST_HANDLER_CONTEXT:
        client.execute(httpUriRequest, new BasicResponseHandler(), new BasicHttpContext());
        break;
      case HOST_REQUEST_HANDLER:
        client.execute(host, httpRequest, new BasicResponseHandler());
        break;
      case HOST_REQUEST_HANDLER_CONTEXT:
        client.execute(host, httpRequest, new BasicResponseHandler(), new BasicHttpContext());
        break;
      case HOST_REQUEST_CONTEXT:
        client.execute(host, httpRequest, new BasicHttpContext());
        break;
      default:
        throw new IllegalArgumentException("Unknown execute method: " + executeMethod);
    }
  }

  private HttpClient getHttpClient(final Client client) {
    switch (client) {
      case DefaultHttpClient:
        return new DefaultHttpClient();
        /*
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

           */
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

  public enum Request {
    HttpRequest,
    HttpUriRequest
  }

  public enum ExecuteMethod {
    REQUEST,
    REQUEST_CONTEXT,
    HOST_REQUEST,
    REQUEST_HANDLER,
    REQUEST_HANDLER_CONTEXT,
    HOST_REQUEST_HANDLER,
    HOST_REQUEST_HANDLER_CONTEXT,
    HOST_REQUEST_CONTEXT;
  }
}
