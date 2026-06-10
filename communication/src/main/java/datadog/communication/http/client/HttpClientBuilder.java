package datadog.communication.http.client;

import datadog.communication.http.HttpRetryPolicy;
import javax.annotation.Nullable;

/**
 * Common builder contract for facade HTTP clients, independent from the underlying implementation.
 */
public interface HttpClientBuilder<B extends HttpClientBuilder<B>> {

  B transport(HttpTransport transport);

  B unixDomainSocketPath(@Nullable String unixDomainSocketPath);

  B namedPipe(@Nullable String namedPipe);

  B connectTimeoutMillis(long connectTimeoutMillis);

  B requestTimeoutMillis(long requestTimeoutMillis);

  B proxy(String proxyHost, int proxyPort);

  B proxy(
      String proxyHost, int proxyPort, @Nullable String proxyUsername, @Nullable String proxyPassword);

  B retryPolicyFactory(HttpRetryPolicy.Factory retryPolicyFactory);

  HttpClient build();
}
