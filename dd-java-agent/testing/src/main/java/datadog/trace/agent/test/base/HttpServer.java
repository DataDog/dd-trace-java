package datadog.trace.agent.test.base;

import java.net.URI;
import java.util.concurrent.TimeoutException;

public interface HttpServer {

  void start() throws TimeoutException;

  void stop();

  URI address();
}
