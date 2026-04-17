package datadog.trace.common.writer;

import static datadog.json.JsonMapper.toJson;

import datadog.json.JsonWriter;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.civisibility.coverage.TestReport;
import datadog.trace.api.civisibility.coverage.TestReportFileEntry;
import datadog.trace.api.civisibility.coverage.TestReportHolder;
import datadog.trace.api.civisibility.domain.TestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.intake.TrackType;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.Metadata;
import datadog.trace.core.MetadataConsumer;
import datadog.trace.util.PidHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link PayloadDispatcher} that writes CI Visibility payloads as JSON files to a directory,
 * instead of sending them over the network. Used in Bazel's hermetic sandbox where network access
 * is forbidden.
 *
 * <p>Each flush produces a single JSON file named {@code {kind}-{timestamp_ns}-{pid}-{seq}.json},
 * written atomically via a temp file + rename.
 *
 * <p>TODO: unify serialization with msgpack mappers via a format-agnostic abstraction
 */
public class FileBasedPayloadDispatcher implements PayloadDispatcher {

  private static final Logger log = LoggerFactory.getLogger(FileBasedPayloadDispatcher.class);

  private static final Collection<String> TOP_LEVEL_TAGS =
      Arrays.asList(
          Tags.TEST_SESSION_ID, Tags.TEST_MODULE_ID, Tags.TEST_SUITE_ID, Tags.ITR_CORRELATION_ID);

  /** Tag prefixes excluded from file-based payloads to avoid Bazel cache invalidation. */
  private static final String[] EXCLUDED_TAG_PREFIXES = {"ci.", "git.", "runtime.", "os."};

  private static final Set<String> EXCLUDED_TAGS =
      new HashSet<>(Arrays.asList("runtime-id", "pr.number"));

  private final Path outputDir;
  private final String filePrefix;
  private final TrackType trackType;
  private final CiVisibilityWellKnownTags wellKnownTags =
      Config.get().getCiVisibilityWellKnownTags();
  private final List<String> serializedEvents = new ArrayList<>();
  private final AtomicLong sequence = new AtomicLong(0);

  public FileBasedPayloadDispatcher(Path outputDir, String filePrefix, TrackType trackType) {
    this.outputDir = outputDir;
    this.filePrefix = filePrefix;
    this.trackType = trackType;
  }

  private static TestReport getTestReport(CoreSpan<?> span) {
    if (span instanceof AgentSpan) {
      TestContext test =
          ((AgentSpan) span).getRequestContext().getData(RequestContextSlot.CI_VISIBILITY);
      if (test != null) {
        TestReportHolder probes = test.getCoverageStore();
        if (probes != null) {
          return probes.getReport();
        }
      }
    }
    return null;
  }

  private static boolean isExcludedTag(String key) {
    if (EXCLUDED_TAGS.contains(key)) {
      return true;
    }
    for (String prefix : EXCLUDED_TAG_PREFIXES) {
      if (key.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean charSeqEquals(CharSequence a, CharSequence b) {
    return a == null && b == null
        || a != null && b != null && Objects.equals(a.toString(), b.toString());
  }

  @Override
  public void onDroppedTrace(int spanCount) {
    // no-op
  }

  // -- Test event serialization (mirrors CiTestCycleMapperV1.map) --

  @Override
  public void addTrace(List<? extends CoreSpan<?>> trace) {
    if (trace.isEmpty()) {
      return;
    }

    if (trackType == TrackType.CITESTCYCLE) {
      for (CoreSpan<?> span : trace) {
        String json = serializeTestEvent(span);
        if (json != null) {
          serializedEvents.add(json);
        }
      }
    } else if (trackType == TrackType.CITESTCOV) {
      for (CoreSpan<?> span : trace) {
        String json = serializeCoverageEvent(span);
        if (json != null) {
          serializedEvents.add(json);
        }
      }
    }
  }

  // -- Coverage event serialization (mirrors CiTestCovMapperV2.map) --

  @Override
  public void flush() {
    if (serializedEvents.isEmpty()) {
      return;
    }

    try {
      ensureOutputDir();

      JsonWriter doc = new JsonWriter(false);
      doc.beginObject();

      if (trackType == TrackType.CITESTCYCLE) {
        doc.name("version").value(1);
        doc.name("metadata");
        doc.beginObject();
        doc.name("*");
        doc.beginObject();
        doc.name("env").value(wellKnownTags.getEnv().toString());
        doc.name("language").value(wellKnownTags.getLanguage().toString());
        doc.name("test_is_user_provided_service")
            .value(wellKnownTags.getIsUserProvidedService().toString());
        doc.endObject();
        doc.endObject();
        doc.name("events");
      } else {
        doc.name("version").value(2);
        doc.name("coverages");
      }

      doc.beginArray();
      for (String event : serializedEvents) {
        doc.jsonValue(event);
      }
      doc.endArray();
      doc.endObject();

      writeFileAtomically(doc.toByteArray());
    } catch (IOException e) {
      log.error("[bazel mode] Failed to write payload file to {}", outputDir, e);
    } finally {
      serializedEvents.clear();
    }
  }

  @Override
  public Collection<RemoteApi> getApis() {
    return Collections.emptyList();
  }

  // -- Tag writing --

  private String serializeTestEvent(CoreSpan<?> span) {
    DDTraceId testSessionId = span.getTag(Tags.TEST_SESSION_ID);
    Number testModuleId = span.getTag(Tags.TEST_MODULE_ID);
    Number testSuiteId = span.getTag(Tags.TEST_SUITE_ID);
    String itrCorrelationId = span.getTag(Tags.ITR_CORRELATION_ID);

    CharSequence type;
    Long traceId;
    Long spanId;
    Long parentId;
    int version;
    CharSequence spanType = span.getType();
    if (charSeqEquals(InternalSpanTypes.TEST, spanType)) {
      type = InternalSpanTypes.TEST;
      traceId = span.getTraceId().toLong();
      spanId = span.getSpanId();
      parentId = span.getParentId();
      version = (testSessionId != null || testModuleId != null || testSuiteId != null) ? 2 : 1;
    } else if (charSeqEquals(InternalSpanTypes.TEST_SUITE_END, spanType)) {
      type = InternalSpanTypes.TEST_SUITE_END;
      traceId = null;
      spanId = null;
      parentId = null;
      version = 1;
    } else if (charSeqEquals(InternalSpanTypes.TEST_MODULE_END, spanType)) {
      type = InternalSpanTypes.TEST_MODULE_END;
      traceId = null;
      spanId = null;
      parentId = null;
      version = 1;
    } else if (charSeqEquals(InternalSpanTypes.TEST_SESSION_END, spanType)) {
      type = InternalSpanTypes.TEST_SESSION_END;
      traceId = null;
      spanId = null;
      parentId = null;
      version = 1;
    } else {
      type = "span";
      traceId = span.getTraceId().toLong();
      spanId = span.getSpanId();
      parentId = span.getParentId();
      version = 1;
    }

    JsonWriter w = new JsonWriter(false);
    w.beginObject();
    w.name("type").value(type.toString());
    w.name("version").value(version);
    w.name("content");
    w.beginObject();

    if (traceId != null) {
      w.name("trace_id").value(Long.toUnsignedString(traceId));
    }
    if (spanId != null) {
      w.name("span_id").value(Long.toUnsignedString(spanId));
    }
    if (parentId != null) {
      w.name("parent_id").value(Long.toUnsignedString(parentId));
    }
    if (testSessionId != null) {
      w.name(Tags.TEST_SESSION_ID).value(testSessionId.toLong());
    }
    if (testModuleId != null) {
      w.name(Tags.TEST_MODULE_ID).value(testModuleId.longValue());
    }
    if (testSuiteId != null) {
      w.name(Tags.TEST_SUITE_ID).value(testSuiteId.longValue());
    }
    if (itrCorrelationId != null) {
      w.name(Tags.ITR_CORRELATION_ID).value(itrCorrelationId);
    }

    w.name("service").value(span.getServiceName());
    w.name("name").value(String.valueOf(span.getOperationName()));
    w.name("resource").value(String.valueOf(span.getResourceName()));
    w.name("start").value(span.getStartTime());
    w.name("duration").value(span.getDurationNano());
    w.name("error").value(span.getError());

    span.processTagsAndBaggage(new JsonMetaWriter(w));

    w.endObject(); // content
    w.endObject(); // event
    return w.toString();
  }

  private String serializeCoverageEvent(CoreSpan<?> span) {
    CharSequence type = span.getType();
    if (type == null || !type.toString().contentEquals(InternalSpanTypes.TEST)) {
      return null;
    }

    TestReport testReport = getTestReport(span);
    if (testReport == null || !testReport.isNotEmpty()) {
      return null;
    }

    JsonWriter w = new JsonWriter(false);
    w.beginObject();

    DDTraceId testSessionId = testReport.getTestSessionId();
    if (testSessionId != null) {
      w.name("test_session_id").value(testSessionId.toLong());
    }
    Long testSuiteId = testReport.getTestSuiteId();
    if (testSuiteId != null) {
      w.name("test_suite_id").value(testSuiteId);
    }
    w.name("span_id").value(testReport.getSpanId());

    w.name("files");
    w.beginArray();
    for (TestReportFileEntry entry : testReport.getTestReportFileEntries()) {
      w.beginObject();
      w.name("filename").value(entry.getSourceFileName());
      BitSet coveredLines = entry.getCoveredLines();
      if (coveredLines != null) {
        w.name("bitmap").value(Base64.getEncoder().encodeToString(coveredLines.toByteArray()));
      }
      w.endObject();
    }
    w.endArray();

    w.endObject();
    return w.toString();
  }

  // -- File I/O --

  private void ensureOutputDir() throws IOException {
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir);
    }
  }

  private void writeFileAtomically(byte[] data) throws IOException {
    long timestampNs = System.nanoTime();
    long pid = PidHelper.getPidAsLong();
    long seq = sequence.getAndIncrement();

    String filename = String.format("%s-%d-%d-%d.json", filePrefix, timestampNs, pid, seq);
    Path target = outputDir.resolve(filename);
    Path tmp = outputDir.resolve(filename + ".tmp");

    Files.write(tmp, data);
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);

    if (log.isDebugEnabled()) {
      log.debug("[bazel mode] Wrote payload file: {} ({} bytes)", target, data.length);
    }
  }

  /** Writes span meta/metrics as JSON, filtering out CI/Git/OS/Runtime tags. */
  private static final class JsonMetaWriter implements MetadataConsumer {
    private final JsonWriter w;

    JsonMetaWriter(JsonWriter w) {
      this.w = w;
    }

    private static void writeNumber(JsonWriter w, Number n) {
      if (n instanceof Double) {
        w.value(n.doubleValue());
      } else if (n instanceof Float) {
        w.value(n.floatValue());
      } else {
        w.value(n.longValue());
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void accept(Metadata metadata) {
      TagMap tags = metadata.getTags().copy();
      for (String topLevel : TOP_LEVEL_TAGS) {
        tags.remove(topLevel);
      }

      w.name("metrics");
      w.beginObject();
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        if (entry.getValue() instanceof Number && !isExcludedTag(entry.getKey())) {
          w.name(entry.getKey());
          writeNumber(w, (Number) entry.getValue());
        }
      }
      w.endObject();

      w.name("meta");
      w.beginObject();
      for (Map.Entry<String, String> entry : metadata.getBaggage().entrySet()) {
        if (!isExcludedTag(entry.getKey())) {
          w.name(entry.getKey()).value(entry.getValue());
        }
      }
      if (metadata.getHttpStatusCode() != null) {
        w.name(Tags.HTTP_STATUS).value(metadata.getHttpStatusCode().toString());
      }
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        Object value = entry.getValue();
        if (!(value instanceof Number) && !isExcludedTag(entry.getKey())) {
          w.name(entry.getKey());
          if (value instanceof Iterable) {
            w.value(toJson((Collection<String>) value));
          } else {
            w.value(String.valueOf(value));
          }
        }
      }
      w.endObject();
    }
  }
}
