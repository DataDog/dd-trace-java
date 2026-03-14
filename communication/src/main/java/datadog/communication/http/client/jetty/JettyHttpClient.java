package datadog.communication.http.client.jetty;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClient;
import datadog.communication.http.client.HttpClientRequest;
import datadog.communication.http.client.HttpClientResponse;
import datadog.communication.http.client.HttpTransport;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.unixsocket.client.HttpClientTransportOverUnixSockets;

final class JettyHttpClient implements HttpClient {
  private final long requestTimeoutMillis;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final org.eclipse.jetty.client.HttpClient client;
  private final boolean closeClientOnClose;
  private final Set<Request> inFlightRequests = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  JettyHttpClient(
      HttpTransport transport,
      @Nullable String unixDomainSocketPath,
      long connectTimeoutMillis,
      long requestTimeoutMillis,
      long responseTimeoutMillis,
      @Nullable String proxyHost,
      @Nullable Integer proxyPort,
      @Nullable String proxyUsername,
      @Nullable String proxyPassword,
      HttpRetryPolicy.Factory retryPolicyFactory,
      @Nullable org.eclipse.jetty.client.HttpClient externallyProvidedClient,
      boolean closeClientOnClose) {
    if (transport == HttpTransport.NAMED_PIPE) {
      throw new IllegalArgumentException("Jetty HTTP client supports only TCP and UDS transport");
    }

    this.requestTimeoutMillis = requestTimeoutMillis;
    this.retryPolicyFactory = retryPolicyFactory;

    if (externallyProvidedClient != null) {
      this.client = externallyProvidedClient;
      this.closeClientOnClose = closeClientOnClose;
      startClient(this.client);
      return;
    }

    org.eclipse.jetty.client.HttpClient client = createClient(transport, unixDomainSocketPath);
    client.setConnectTimeout(connectTimeoutMillis);
    client.setIdleTimeout(responseTimeoutMillis);
    if (proxyHost != null && proxyPort != null) {
      client.getProxyConfiguration().getProxies().add(new HttpProxy(proxyHost, proxyPort));
      if (proxyUsername != null) {
        URI proxyUri = URI.create("http://" + proxyHost + ":" + proxyPort);
        client
            .getAuthenticationStore()
            .addAuthentication(
                new BasicAuthentication(
                    proxyUri, Authentication.ANY_REALM, proxyUsername, proxyPassword));
      }
    }
    startClient(client);
    this.client = client;
    this.closeClientOnClose = true;
  }

  @Override
  public HttpClientResponse execute(HttpClientRequest request) throws IOException {
    if (closed) {
      throw new IOException("http client is closed");
    }

    try (HttpRetryPolicy retryPolicy = retryPolicyFactory.create()) {
      while (true) {
        try {
          HttpClientResponse response = executeOnce(request);
          if (!retryPolicy.shouldRetry(new ResponseAdapter(response))) {
            return response;
          }
        } catch (Exception e) {
          IOException io = e instanceof IOException ? (IOException) e : new IOException(e);
          if (!retryPolicy.shouldRetry(io)) {
            throw io;
          }
        }
        retryPolicy.backoff();
      }
    }
  }

  private HttpClientResponse executeOnce(HttpClientRequest request) throws IOException {
    Request jettyRequest = toJettyRequest(request);
    inFlightRequests.add(jettyRequest);
    try {
      ContentResponse response =
          jettyRequest.timeout(requestTimeoutMillis, TimeUnit.MILLISECONDS).send();
      return toFacadeResponse(response);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("request interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw cause instanceof IOException ? (IOException) cause : new IOException(cause);
    } catch (TimeoutException e) {
      throw new IOException("request timeout", e);
    } finally {
      inFlightRequests.remove(jettyRequest);
    }
  }

  private Request toJettyRequest(HttpClientRequest request) {
    Request jettyRequest = client.newRequest(request.uri()).method(request.method());
    for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
      for (String value : header.getValue()) {
        jettyRequest.header(header.getKey(), value);
      }
    }

    byte[] body = request.body();
    if (body.length > 0) {
      jettyRequest.content(new BytesContentProvider(body));
    }
    return jettyRequest;
  }

  private static HttpClientResponse toFacadeResponse(ContentResponse response) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (HttpField header : response.getHeaders()) {
      headers
          .computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
          .add(header.getValue());
    }
    return new HttpClientResponse(response.getStatus(), headers, response.getContent());
  }

  @Override
  public void close() {
    closed = true;

    for (Request request : inFlightRequests) {
      request.abort(new IOException("http client is closed"));
    }
    inFlightRequests.clear();

    if (closeClientOnClose) {
      try {
        client.stop();
      } catch (Exception ignored) {
      }
    }
  }

  private static void startClient(org.eclipse.jetty.client.HttpClient client) {
    if (!client.isStarted()) {
      try {
        client.start();
      } catch (Exception e) {
        throw new IllegalStateException("Unable to start Jetty HTTP client", e);
      }
    }
  }

  private static org.eclipse.jetty.client.HttpClient createClient(
      HttpTransport transport, @Nullable String unixDomainSocketPath) {
    if (transport == HttpTransport.UNIX_DOMAIN_SOCKET) {
      HttpClientTransport udsTransport =
          new HttpClientTransportOverUnixSockets(unixDomainSocketPath);
      return new org.eclipse.jetty.client.HttpClient(udsTransport, null);
    }
    return new org.eclipse.jetty.client.HttpClient();
  }

  private static final class ResponseAdapter implements HttpRetryPolicy.Response {
    private final HttpClientResponse response;

    private ResponseAdapter(HttpClientResponse response) {
      this.response = response;
    }

    @Override
    public int code() {
      return response.statusCode();
    }

    @Override
    public @Nullable String header(String name) {
      return response.header(name);
    }
  }
}
