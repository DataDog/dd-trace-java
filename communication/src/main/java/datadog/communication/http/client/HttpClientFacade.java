package datadog.communication.http.client;

import java.io.IOException;

/** Facade for synchronous HTTP calls independent from the underlying client implementation. */
public interface HttpClientFacade extends AutoCloseable {

  HttpClientResponse execute(HttpClientRequest request) throws IOException;

  @Override
  void close();
}
