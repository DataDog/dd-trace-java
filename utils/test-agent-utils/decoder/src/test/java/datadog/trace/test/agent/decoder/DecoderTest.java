package datadog.trace.test.agent.decoder;

import static datadog.trace.test.util.AssertionsUtils.assertMapContainsKeyValues;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class DecoderTest {

  @Test
  public void decode() throws Throwable {
    String resourceName = "/greeting.msgpack";
    byte[] buffer = readResourceFile(resourceName);
    DecodedMessage message = Decoder.decode(buffer);
    List<DecodedTrace> traces = message.getTraces();
    assertEquals(1, traces.size());
    List<DecodedSpan> spans = traces.get(0).getSpans();
    assertEquals(2, spans.size());
    List<DecodedSpan> sorted = Decoder.sortByStart(spans);
    DecodedSpan first = sorted.get(0);
    long traceId = first.getTraceId();
    validateSpan(
        first,
        traceId,
        0,
        "servlet.request",
        "GET /greeting",
        "smoke-test-java-app",
        "web",
        singletonMap("component", "tomcat-server"),
        singletonMap("_dd.top_level", 1));
    DecodedSpan second = sorted.get(1);
    validateSpan(
        second,
        traceId,
        first.getSpanId(),
        "spring.handler",
        "WebController.greeting",
        "smoke-test-java-app",
        "web",
        singletonMap("component", "spring-web-controller"),
        singletonMap("_dd.measured", 1));
  }

  @Test
  public void decode04() throws Throwable {
    byte[] buffer = readResourceFile("/webflux.04.msgpack");

    DecodedMessage message = Decoder.decodeV04(buffer);
    List<DecodedTrace> traces = message.getTraces();
    assertEquals(1, traces.size());
    List<DecodedSpan> spans = traces.get(0).getSpans();
    assertEquals(2, spans.size());
    List<DecodedSpan> sorted = Decoder.sortByStart(spans);

    DecodedSpan first = sorted.get(0);
    long traceId = first.getTraceId();
    validateSpan(
        first,
        traceId,
        0,
        "netty.request",
        "GET /hello",
        "smoke-test-java-app",
        "web",
        singletonMap("component", "netty"),
        singletonMap("_dd.agent_psr", 1.0));

    DecodedSpan second = sorted.get(1);
    validateSpan(
        second,
        traceId,
        first.getSpanId(),
        "WebController.hello",
        "WebController.hello",
        "smoke-test-java-app",
        "web",
        singletonMap("component", "spring-webflux-controller"),
        singletonMap("_dd.measured", 1));
  }

  @Test
  public void decodeV1() throws Throwable {
    byte[] buffer = readResourceFile("/sample_v1.msgpack");

    DecodedMessage message = Decoder.decodeV1(buffer);
    List<DecodedTrace> traces = message.getTraces();
    assertEquals(1, traces.size());
    List<DecodedSpan> spans = traces.get(0).getSpans();
    assertEquals(2, spans.size());
    List<DecodedSpan> sorted = Decoder.sortByStart(spans);

    DecodedSpan first = sorted.get(0);
    long traceId = first.getTraceId();
    validateSpan(
        first,
        traceId,
        0,
        "netty.request",
        "GET /hello",
        "smoke-test-java-app",
        "web",
        singletonMap("component", "netty"),
        singletonMap("_dd.agent_psr", 1.0));

    DecodedSpan second = sorted.get(1);
    validateSpan(
        second,
        traceId,
        first.getSpanId(),
        "WebController.hello",
        "WebController.hello",
        "smoke-test-java-app",
        "web",
        singletonMap("component", "spring-webflux-controller"),
        singletonMap("_dd.measured", 1));
  }

  @Test
  public void sortByStart() throws Throwable {
    byte[] buffer = readResourceFile("/greeting.msgpack");
    DecodedMessage message = Decoder.decode(buffer);
    List<DecodedTrace> traces = message.getTraces();
    assertEquals(1, traces.size());
    final List<DecodedSpan> spans = traces.get(0).getSpans();
    assertEquals(2, spans.size());
    final List<DecodedSpan> sorted = Decoder.sortByStart(spans);
    assertTrue(sorted.get(0).getStart() < sorted.get(1).getStart());
    // Ensure that we cover all the branches
    List<DecodedSpan> tmp = Decoder.sortByStart(sorted);
    assertIterableEquals(sorted, tmp);
    Collections.reverse(tmp);
    tmp = Decoder.sortByStart(tmp);
    assertIterableEquals(sorted, tmp);
    tmp.set(1, tmp.get(0));
    assertIterableEquals(tmp, Decoder.sortByStart(tmp));
  }

  private byte[] readResourceFile(String resourceName) throws IOException {
    Path path = new File(getClass().getResource(resourceName).getFile()).toPath();
    return Files.readAllBytes(path);
  }

  public static void validateSpan(
      DecodedSpan span,
      long traceId,
      long parentId,
      String name,
      String resource,
      String service,
      String type,
      Map<String, String> meta,
      Map<String, ? extends Number> metrics) {
    assertEquals(traceId, span.getTraceId());
    assertEquals(parentId, span.getParentId());
    assertEquals(name, span.getName());
    assertEquals(resource, span.getResource());
    assertEquals(service, span.getService());
    assertTrue(span.getDuration() > 0);
    assertEquals(0, span.getError());
    assertEquals(type, span.getType());
    assertMapContainsKeyValues(span.getMeta(), meta);
    assertMapContainsKeyValues(span.getMetrics(), metrics);
  }
}
