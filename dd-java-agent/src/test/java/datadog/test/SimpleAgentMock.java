package datadog.test;

import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Simple web server to mock responses from agent and collect spans. We can not use `TestHttpServer`
 * from `:dd-java-agent:testing` because it will bring unwanted dependencies into classpath, and we
 * are trying to make sure that we expose as little as possible into the bootstrap and system class
 * loaders.
 */
public class SimpleAgentMock implements Closeable {
  private static final MockResponse EMPTY_200_RESPONSE = new MockResponse().setResponseCode(200);

  private final MockWebServer server;
  private final List<DecodedSpan> spans = new CopyOnWriteArrayList<>();

  public SimpleAgentMock() {
    server = new MockWebServer();

    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(final RecordedRequest request) {
            if ("/v0.4/traces".equals(request.getPath())) {
              byte[] body = request.getBody().readByteArray();

              DecodedMessage message = Decoder.decodeV04(body);
              for (DecodedTrace trace : message.getTraces()) {
                spans.addAll(trace.getSpans());
              }
            }

            return EMPTY_200_RESPONSE;
          }
        });
  }

  public SimpleAgentMock start() throws IOException {
    server.start();

    return this;
  }

  public int getPort() {
    return server.getPort();
  }

  public List<DecodedSpan> getSpans() {
    return spans;
  }

  @Override
  public void close() throws IOException {
    server.shutdown();
  }
}
