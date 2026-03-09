package datadog.communication.http.client;

/** Factory for creating transport-specific HTTP client facades. */
public interface HttpClientFactory {

  HttpClient create();
}
