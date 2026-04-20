package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.intake.TrackType;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBasedPayloadDispatcherTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void flushWithNoEventsIsNoOp(@TempDir Path outputDir) throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);

    dispatcher.flush();

    assertTrue(listFiles(outputDir).isEmpty());
  }

  @Test
  void addTraceIsNoOpForEmptyTraces(@TempDir Path outputDir) throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);

    dispatcher.addTrace(Collections.emptyList());
    dispatcher.flush();

    assertTrue(listFiles(outputDir).isEmpty());
  }

  @Test
  void onDroppedTraceIsNoOpAndGetApisReturnsEmpty(@TempDir Path outputDir) {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);

    assertTrue(dispatcher.getApis().isEmpty());
    dispatcher.onDroppedTrace(42); // does not throw
  }

  @Test
  void citestcycleFlushWritesEnvelopeWithVersionMetadataAndEvents(@TempDir Path outputDir)
      throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);
    Map<String, Object> tags = new HashMap<>();
    tags.put(Tags.TEST_SESSION_ID, DDTraceId.from(123));
    tags.put(Tags.TEST_MODULE_ID, 456L);
    tags.put(Tags.TEST_SUITE_ID, 789L);
    tags.put(Tags.ITR_CORRELATION_ID, "corr-1");
    CoreSpan<?> span = mockSpan(InternalSpanTypes.TEST, tags);

    dispatcher.addTrace(Collections.singletonList(span));
    dispatcher.flush();

    List<Path> files = listFiles(outputDir);
    assertEquals(1, files.size());
    String filename = files.get(0).getFileName().toString();
    assertTrue(filename.startsWith("tests-"));
    assertTrue(filename.endsWith(".json"));

    JsonNode doc = JSON.readTree(files.get(0).toFile());
    assertEquals(1, doc.get("version").asInt());
    assertTrue(doc.has("metadata"));
    assertTrue(doc.get("metadata").has("*"));
    assertTrue(doc.get("events").isArray());
    assertEquals(1, doc.get("events").size());

    JsonNode event = doc.get("events").get(0);
    assertEquals("test", event.get("type").asText());
    assertEquals(2, event.get("version").asInt()); // has session/module/suite id
    JsonNode content = event.get("content");
    assertEquals(123L, content.get(Tags.TEST_SESSION_ID).asLong());
    assertEquals(456L, content.get(Tags.TEST_MODULE_ID).asLong());
    assertEquals(789L, content.get(Tags.TEST_SUITE_ID).asLong());
    assertEquals("corr-1", content.get(Tags.ITR_CORRELATION_ID).asText());
  }

  @Test
  void citestcycleStripsCiGitOsRuntimeTagsAndWellKnownFields(@TempDir Path outputDir)
      throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);
    Map<String, Object> tags = new HashMap<>();
    tags.put("ci.provider.name", "github");
    tags.put("git.branch", "main");
    tags.put("os.name", "linux");
    tags.put("runtime.name", "openjdk");
    tags.put("runtime-id", "uuid");
    tags.put("pr.number", "42");
    tags.put("custom.tag", "kept");
    tags.put("kept.metric", 99L);
    CoreSpan<?> span = mockSpan(InternalSpanTypes.TEST, tags);

    dispatcher.addTrace(Collections.singletonList(span));
    dispatcher.flush();

    JsonNode doc = JSON.readTree(listFiles(outputDir).get(0).toFile());
    JsonNode content = doc.get("events").get(0).get("content");
    JsonNode meta = content.get("meta");
    JsonNode metrics = content.get("metrics");

    assertFalse(meta.has("ci.provider.name"));
    assertFalse(meta.has("git.branch"));
    assertFalse(meta.has("os.name"));
    assertFalse(meta.has("runtime.name"));
    assertFalse(meta.has("runtime-id"));
    assertFalse(meta.has("pr.number"));

    assertEquals("kept", meta.get("custom.tag").asText());
    assertEquals(99L, metrics.get("kept.metric").asLong());
  }

  @Test
  void citestcycleAssignsEventTypesForSessionModuleSuiteTestSpanSpans(@TempDir Path outputDir)
      throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);
    List<CoreSpan<?>> spans =
        Arrays.asList(
            mockSpan(InternalSpanTypes.TEST_SESSION_END, Collections.emptyMap()),
            mockSpan(InternalSpanTypes.TEST_MODULE_END, Collections.emptyMap()),
            mockSpan(InternalSpanTypes.TEST_SUITE_END, Collections.emptyMap()),
            mockSpan(InternalSpanTypes.TEST, Collections.emptyMap()),
            mockSpan("other-span-type", Collections.emptyMap()));

    dispatcher.addTrace(spans);
    dispatcher.flush();

    JsonNode events = JSON.readTree(listFiles(outputDir).get(0).toFile()).get("events");
    assertEquals(5, events.size());
    assertEquals(InternalSpanTypes.TEST_SESSION_END.toString(), events.get(0).get("type").asText());
    assertEquals(InternalSpanTypes.TEST_MODULE_END.toString(), events.get(1).get("type").asText());
    assertEquals(InternalSpanTypes.TEST_SUITE_END.toString(), events.get(2).get("type").asText());
    assertEquals(InternalSpanTypes.TEST.toString(), events.get(3).get("type").asText());
    assertEquals("span", events.get(4).get("type").asText());
    // session/module/suite events do not have trace/span/parent ids
    assertFalse(events.get(0).get("content").has("trace_id"));
    assertFalse(events.get(1).get("content").has("trace_id"));
    assertFalse(events.get(2).get("content").has("trace_id"));
    // test and span events do
    assertTrue(events.get(3).get("content").has("trace_id"));
    assertTrue(events.get(4).get("content").has("trace_id"));
  }

  @Test
  void citestcovSkipsNonTestSpansAndEmptyReports(@TempDir Path outputDir) throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "coverage", TrackType.CITESTCOV);
    CoreSpan<?> nonTest = mockSpan("not-a-test", Collections.emptyMap());
    CoreSpan<?> testNoCoverage = mockSpan(InternalSpanTypes.TEST, Collections.emptyMap());

    dispatcher.addTrace(Arrays.asList(nonTest, testNoCoverage));
    dispatcher.flush();

    assertTrue(listFiles(outputDir).isEmpty());
  }

  @Test
  void filenameFollowsPrefixNsPidSeqConvention(@TempDir Path outputDir) throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);
    CoreSpan<?> span = mockSpan(InternalSpanTypes.TEST, Collections.emptyMap());

    dispatcher.addTrace(Collections.singletonList(span));
    dispatcher.flush();
    dispatcher.addTrace(Collections.singletonList(span));
    dispatcher.flush();

    List<String> filenames = new ArrayList<>();
    for (Path p : listFiles(outputDir)) {
      filenames.add(p.getFileName().toString());
    }
    Collections.sort(filenames);

    assertEquals(2, filenames.size());
    Pattern expected = Pattern.compile("tests-\\d+-\\d+-\\d+\\.json");
    for (String name : filenames) {
      assertTrue(expected.matcher(name).matches(), "filename does not match pattern: " + name);
    }
    // sequence is the last number — must differ between the two files
    assertFalse(filenames.get(0).equals(filenames.get(1)));
  }

  @Test
  void flushClearsAccumulatorSoSubsequentFlushIsNoOp(@TempDir Path outputDir) throws IOException {
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(outputDir, "tests", TrackType.CITESTCYCLE);
    CoreSpan<?> span = mockSpan(InternalSpanTypes.TEST, Collections.emptyMap());

    dispatcher.addTrace(Collections.singletonList(span));
    dispatcher.flush();
    dispatcher.flush();

    assertEquals(1, listFiles(outputDir).size());
  }

  @Test
  void outputDirectoryIsCreatedOnFirstFlush(@TempDir Path tmp) throws IOException {
    Path nested = tmp.resolve("nested/does-not-exist");
    assertFalse(Files.exists(nested));
    FileBasedPayloadDispatcher dispatcher =
        new FileBasedPayloadDispatcher(nested, "tests", TrackType.CITESTCYCLE);
    dispatcher.addTrace(
        Collections.singletonList(mockSpan(InternalSpanTypes.TEST, Collections.emptyMap())));

    dispatcher.flush();

    assertTrue(Files.isDirectory(nested));
    assertEquals(1, listFiles(nested).size());
  }

  @SuppressWarnings("unchecked")
  private static CoreSpan<?> mockSpan(CharSequence type, Map<String, Object> tags) {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.getType()).thenReturn(type);
    when(span.getTraceId()).thenReturn(DDTraceId.from(1L));
    when(span.getSpanId()).thenReturn(100L);
    when(span.getParentId()).thenReturn(0L);
    when(span.getServiceName()).thenReturn("service");
    when(span.getOperationName()).thenReturn("operation");
    when(span.getResourceName()).thenReturn("resource");
    when(span.getStartTime()).thenReturn(1_000_000L);
    when(span.getDurationNano()).thenReturn(500_000L);
    when(span.getError()).thenReturn(0);

    // getTag is called for known top-level tags; return a value when present, else null
    when(span.getTag(any(String.class))).thenAnswer(inv -> tags.get((String) inv.getArgument(0)));

    // processTagsAndBaggage invokes the consumer with a Metadata built from the tags map
    Metadata metadata =
        new Metadata(
            Thread.currentThread().getId(),
            null,
            TagMap.fromMap(tags),
            Collections.<String, String>emptyMap(),
            0,
            false,
            false,
            null,
            null,
            0,
            null);
    doAnswer(
            inv -> {
              MetadataConsumer consumer = inv.getArgument(0);
              consumer.accept(metadata);
              return null;
            })
        .when(span)
        .processTagsAndBaggage(any(MetadataConsumer.class));

    return span;
  }

  private static List<Path> listFiles(Path dir) throws IOException {
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path p : stream) {
        files.add(p);
      }
    }
    return files;
  }
}
