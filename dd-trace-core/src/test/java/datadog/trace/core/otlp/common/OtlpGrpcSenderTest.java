package datadog.trace.core.otlp.common;

import static datadog.trace.core.otlp.common.OtlpPayload.PROTOBUF_CONTENT_TYPE;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.api.config.OtlpConfig.Compression;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import java.nio.ByteBuffer;
import okhttp3.Protocol;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

class OtlpGrpcSenderTest {

  private static final String SIGNAL_PATH =
      "/opentelemetry.proto.collector.trace.v1.TraceService/Export";

  @Test
  void grpcErrorStatusInHeadersIsAFailureWithTheCodeInItsMessage() throws Exception {
    // a "trailers-only" response: the status arrives with the headers of an HTTP 200 response
    MockResponse unauthenticated =
        grpcResponse().addHeader("grpc-status", "16").addHeader("grpc-message", "bad api key");

    RemoteApi.Response response = sendOverHttp2(unauthenticated);

    assertFalse(response.success());
    IOException error = assertInstanceOf(IOException.class, response.exception().orElse(null));
    assertEquals("gRPC status 16: bad api key", error.getMessage());
  }

  private static RemoteApi.Response sendOverHttp2(MockResponse mockResponse)
      throws IOException, InterruptedException {
    try (MockWebServer server = new MockWebServer()) {
      server.setProtocols(singletonList(Protocol.H2_PRIOR_KNOWLEDGE));
      server.enqueue(mockResponse);
      server.start();

      OtlpGrpcSender sender = sender("http://" + server.getHostName() + ":" + server.getPort());
      try {
        RemoteApi.Response response =
            sender.send(
                new OtlpPayload(ByteBuffer.wrap(new byte[] {1, 2, 3}), PROTOBUF_CONTENT_TYPE));

        RecordedRequest request = server.takeRequest(5, SECONDS);
        assertNotNull(request);
        assertEquals(SIGNAL_PATH, request.getPath());
        assertEquals(1, server.getRequestCount());
        return response;
      } finally {
        sender.shutdown();
      }
    }
  }

  private static MockResponse grpcResponse() {
    return new MockResponse().setResponseCode(200).addHeader("content-type", "application/grpc");
  }

  private static OtlpGrpcSender sender(String endpoint) {
    return new OtlpGrpcSender(endpoint, SIGNAL_PATH, emptyMap(), 5_000, Compression.NONE);
  }
}
