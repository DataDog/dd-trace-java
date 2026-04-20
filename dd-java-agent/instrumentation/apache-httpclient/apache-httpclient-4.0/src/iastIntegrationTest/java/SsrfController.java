import java.io.IOException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SsrfController {

  private static final Logger logger = LoggerFactory.getLogger(SsrfController.class);

  public String apacheSsrf(
      final String url,
      final String clientClassName,
      final String method,
      final String requestType,
      final String scheme) {
    try {
      HttpClient httpClient = getHttpClient(clientClassName);
      execute(httpClient, url, ExecuteMethod.valueOf(method), requestType, scheme);
    } catch (Exception e) {
      logger.error("Error executing request", e);
      return "NO_OK";
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
    HttpRequest httpRequest = isUriRequest ? httpUriRequest : new BasicHttpRequest("GET", url);
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

  private HttpClient getHttpClient(final String clientClassName) {
    try {
      Class<?> clientClass = Class.forName(clientClassName);
      clientClass.getDeclaredConstructor().setAccessible(true);
      return (HttpClient) clientClass.getDeclaredConstructor().newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException("Unknown client class: " + clientClassName, e);
    }
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
    HOST_REQUEST_CONTEXT
  }
}
