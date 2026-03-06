package datadog.communication.http.ahc;

import datadog.communication.http.HttpRetryPolicy;
import datadog.communication.http.client.HttpClientFacade;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;

final class ApacheAsyncHttpClient implements HttpClientFacade {
  private final long requestTimeoutMillis;
  private final HttpRetryPolicy.Factory retryPolicyFactory;
  private final CloseableHttpAsyncClient client;
  private final boolean closeClientOnClose;
  private final Set<Future<SimpleHttpResponse>> inFlightRequests = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  ApacheAsyncHttpClient(
      HttpTransport transport,
      long connectTimeoutMillis,
      long requestTimeoutMillis,
      long responseTimeoutMillis,
      @Nullable String proxyHost,
      @Nullable Integer proxyPort,
      @Nullable String proxyUsername,
      @Nullable String proxyPassword,
      HttpRetryPolicy.Factory retryPolicyFactory,
      @Nullable CloseableHttpAsyncClient externallyProvidedClient,
      boolean closeClientOnClose) {
    if (transport != HttpTransport.TCP) {
      throw new IllegalArgumentException("Apache async client supports TCP transport only");
    }

    this.requestTimeoutMillis = requestTimeoutMillis;
    this.retryPolicyFactory = retryPolicyFactory;

    if (externallyProvidedClient != null) {
      this.client = externallyProvidedClient;
      this.closeClientOnClose = closeClientOnClose;
      this.client.start();
      return;
    }

    RequestConfig.Builder configBuilder =
        RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMillis))
            .setResponseTimeout(Timeout.ofMilliseconds(responseTimeoutMillis))
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(requestTimeoutMillis));

    BasicCredentialsProvider credentialsProvider = null;
    if (proxyHost != null && proxyPort != null) {
      configBuilder.setProxy(new HttpHost("http", proxyHost, proxyPort));
      if (proxyUsername != null) {
        credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(
            new AuthScope(proxyHost, proxyPort),
            new UsernamePasswordCredentials(proxyUsername, asChars(proxyPassword)));
      }
    }

    org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder clientBuilder =
        HttpAsyncClients.custom().setDefaultRequestConfig(configBuilder.build());
    if (credentialsProvider != null) {
      clientBuilder.setDefaultCredentialsProvider(credentialsProvider);
    }

    this.client = clientBuilder.build();
    this.client.start();
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
    SimpleHttpRequest httpRequest = toApacheRequest(request);

    Future<SimpleHttpResponse> future = client.execute(httpRequest, null);
    inFlightRequests.add(future);
    try {
      SimpleHttpResponse response = future.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
      return toFacadeResponse(response);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("request interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw cause instanceof IOException ? (IOException) cause : new IOException(cause);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IOException("request timeout", e);
    } finally {
      inFlightRequests.remove(future);
    }
  }

  private static SimpleHttpRequest toApacheRequest(HttpClientRequest request) {
    URI uri = request.uri();
    SimpleRequestBuilder requestBuilder = SimpleRequestBuilder.create(request.method()).setUri(uri);

    for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
      for (String value : header.getValue()) {
        requestBuilder.addHeader(header.getKey(), value);
      }
    }

    byte[] body = request.body();
    if (body.length > 0) {
      requestBuilder.setBody(body, ContentType.APPLICATION_OCTET_STREAM);
    }

    return requestBuilder.build();
  }

  private static HttpClientResponse toFacadeResponse(SimpleHttpResponse response) {
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (Header header : response.getHeaders()) {
      headers
          .computeIfAbsent(header.getName(), ignored -> new ArrayList<>())
          .add(header.getValue());
    }

    byte[] body = response.getBodyBytes() != null ? response.getBodyBytes() : new byte[0];
    return new HttpClientResponse(response.getCode(), headers, body);
  }

  @Override
  public void close() {
    closed = true;

    for (Future<SimpleHttpResponse> future : inFlightRequests) {
      future.cancel(true);
    }
    inFlightRequests.clear();

    if (closeClientOnClose) {
      try {
        client.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static char[] asChars(@Nullable String value) {
    return value == null ? new char[0] : value.toCharArray();
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
