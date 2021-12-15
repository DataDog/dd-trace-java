package datadog.trace.test.agent.decoder;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Collections.singletonMap;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DecoderTest {

  @Test
  public void decode() throws Throwable {
    byte[] buffer =
        Files.readAllBytes(
            new File(this.getClass().getResource("/greeting.msgpack").getFile()).toPath());
    DecodedMessage message = Decoder.decode(buffer);
    List<DecodedTrace> traces = message.getTraces();
    assertThat(traces).hasSize(1);
    List<DecodedSpan> spans = traces.get(0).getSpans();
    assertThat(spans).hasSize(2);
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
  public void sortByStart() throws Throwable {
    byte[] buffer =
        Files.readAllBytes(
            new File(this.getClass().getResource("/greeting.msgpack").getFile()).toPath());
    DecodedMessage message = Decoder.decode(buffer);
    List<DecodedTrace> traces = message.getTraces();
    assertThat(traces).hasSize(1);
    final List<DecodedSpan> spans = traces.get(0).getSpans();
    assertThat(spans).hasSize(2);
    final List<DecodedSpan> sorted = Decoder.sortByStart(spans);
    assertThat(sorted.get(0).getStart()).isLessThan(sorted.get(1).getStart());
    // Ensure that we cover all the branches
    List<DecodedSpan> tmp = Decoder.sortByStart(sorted);
    assertThat(tmp).containsExactlyElementsIn(sorted);
    Collections.reverse(tmp);
    tmp = Decoder.sortByStart(tmp);
    assertThat(tmp).containsExactlyElementsIn(sorted);
    tmp.set(1, tmp.get(0));
    assertThat(Decoder.sortByStart(tmp)).containsExactlyElementsIn(tmp);
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
    assertThat(span.getTraceId()).isEqualTo(traceId);
    assertThat(span.getParentId()).isEqualTo(parentId);
    assertThat(span.getName()).isEqualTo(name);
    assertThat(span.getResource()).isEqualTo(resource);
    assertThat(span.getService()).isEqualTo(service);
    assertThat(span.getDuration()).isGreaterThan(0);
    assertThat(span.getError()).isEqualTo(0);
    assertThat(span.getType()).isEqualTo(type);
    assertThat(span.getMeta()).containsAtLeastEntriesIn(meta);
    assertThat(span.getMetrics()).containsAtLeastEntriesIn(metrics);
  }
}
