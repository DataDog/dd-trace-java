package datadog.trace.common.metrics;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.junit.utils.config.WithConfigExtension.removeSysConfig;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.tabletest.junit.TableTest;

class SerializingMetricWriterTest extends DDJavaSpecification {

  @BeforeAll
  static void registerHistograms() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  /** Build an {@link AggregateEntry} with a pre-recorded duration count. */
  private static AggregateEntry entry(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      CharSequence serviceSource,
      CharSequence type,
      int httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      CharSequence spanKind,
      List<UTF8BytesString> peerTags,
      CharSequence httpMethod,
      CharSequence httpEndpoint,
      CharSequence grpcStatusCode,
      int hitCount) {
    AggregateEntry aggregateEntry =
        AggregateEntryTestUtils.of(
            resource,
            service,
            operationName,
            serviceSource,
            type,
            httpStatusCode,
            synthetic,
            traceRoot,
            spanKind,
            peerTags,
            httpMethod,
            httpEndpoint,
            grpcStatusCode);
    for (int i = 0; i < hitCount; i++) {
      aggregateEntry.recordOneDuration(1L);
    }
    return aggregateEntry;
  }

  static Stream<Arguments> shouldProduceCorrectMessageArguments() {
    List<AggregateEntry> smallContent =
        Arrays.asList(
            entry(
                "resource1",
                "service1",
                "operation1",
                null,
                "type",
                0,
                false,
                false,
                "client",
                Arrays.asList(
                    UTF8BytesString.create("country:canada"),
                    UTF8BytesString.create("georegion:amer"),
                    UTF8BytesString.create("peer.service:remote-service")),
                null,
                null,
                null,
                10),
            entry(
                "resource2",
                "service2",
                "operation2",
                null,
                "type2",
                200,
                true,
                false,
                "producer",
                Arrays.asList(
                    UTF8BytesString.create("country:canada"),
                    UTF8BytesString.create("georegion:amer"),
                    UTF8BytesString.create("peer.service:remote-service")),
                null,
                null,
                null,
                9),
            entry(
                "GET /api/users/:id",
                "web-service",
                "http.request",
                null,
                "web",
                200,
                false,
                true,
                "server",
                Collections.<UTF8BytesString>emptyList(),
                null,
                null,
                null,
                5));

    List<AggregateEntry> largeContent = new ArrayList<>();
    for (int i = 0; i <= 10000; i++) {
      largeContent.add(
          entry(
              "resource" + i,
              "service" + i,
              "operation" + i,
              null,
              "type",
              0,
              false,
              false,
              "producer",
              Collections.singletonList(UTF8BytesString.create("messaging.destination:dest" + i)),
              null,
              null,
              null,
              10));
    }

    return Stream.of(
        arguments("small content with process tags", smallContent, true),
        arguments("large content without process tags", largeContent, false));
  }

  @ParameterizedTest(name = "should produce correct message: {0}")
  @MethodSource("shouldProduceCorrectMessageArguments")
  void shouldProduceCorrectMessage(
      String scenario, List<AggregateEntry> content, boolean withProcessTags) {
    // setup
    if (!withProcessTags) {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false");
    }
    ProcessTags.reset(Config.get());
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    try {
      // when
      writer.startBucket(content.size(), startTime, duration);
      for (AggregateEntry aggregateEntry : content) {
        writer.add(aggregateEntry);
      }
      writer.finishBucket();

      // then
      assertTrue(sink.validatedInput());
    } finally {
      // cleanup
      removeSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED);
      ProcessTags.reset(Config.get());
    }
  }

  @Test
  void serviceSourceOptionalInThePayload() {
    // setup
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    AggregateEntry entryNoSource =
        entry(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            "GET",
            "/api/users",
            null,
            1);
    AggregateEntry entryWithSource =
        entry(
            "resource",
            "service",
            "operation",
            "source",
            "type",
            200,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            "POST",
            null,
            null,
            1);

    List<AggregateEntry> content = Arrays.asList(entryNoSource, entryWithSource);

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    // when
    writer.startBucket(content.size(), startTime, duration);
    for (AggregateEntry aggregateEntry : content) {
      writer.add(aggregateEntry);
    }
    writer.finishBucket();

    // then
    assertTrue(sink.validatedInput());
  }

  @Test
  void httpMethodAndHttpEndpointFieldsAreOptionalInPayload() {
    // setup
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    AggregateEntry entryWithBoth =
        entry(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            "GET",
            "/api/users",
            null,
            1);
    AggregateEntry entryWithMethodOnly =
        entry(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            "POST",
            null,
            null,
            1);
    AggregateEntry entryWithEndpointOnly =
        entry(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            null,
            "/api/orders",
            null,
            1);
    AggregateEntry entryWithNeither =
        entry(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "client",
            Collections.<UTF8BytesString>emptyList(),
            null,
            null,
            null,
            1);

    List<AggregateEntry> content =
        Arrays.asList(entryWithBoth, entryWithMethodOnly, entryWithEndpointOnly, entryWithNeither);

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    // when
    writer.startBucket(content.size(), startTime, duration);
    for (AggregateEntry aggregateEntry : content) {
      writer.add(aggregateEntry);
    }
    writer.finishBucket();

    // then
    assertTrue(sink.validatedInput());
  }

  @TableTest({
    "scenario        | shaCommit",
    "no sha commit   |          ",
    "with sha commit | 123456   "
  })
  void addGitShaCommitInfo(String shaCommit) {
    // setup
    GitInfoProvider gitInfoProvider = mock(GitInfoProvider.class);
    when(gitInfoProvider.getGitInfo())
        .thenReturn(new GitInfo(null, null, null, new CommitInfo(shaCommit)));

    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    AggregateEntry aggregateEntry =
        entry(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            "GET",
            "/api/users",
            null,
            1);

    List<AggregateEntry> content = Collections.singletonList(aggregateEntry);

    ValidatingSink sink =
        new ValidatingSink(wellKnownTags, startTime, duration, content, shaCommit);
    SerializingMetricWriter writer =
        new SerializingMetricWriter(wellKnownTags, sink, 128, gitInfoProvider);

    // when
    writer.startBucket(content.size(), startTime, duration);
    for (AggregateEntry entryItem : content) {
      writer.add(entryItem);
    }
    writer.finishBucket();

    // then
    assertTrue(sink.validatedInput());
  }

  @Test
  void grpcStatusCodeFieldIsPresentInPayloadForRpcTypeSpans() {
    // setup
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    AggregateEntry entryWithGrpc =
        entry(
            "grpc.service/Method",
            "grpc-service",
            "grpc.server",
            null,
            "rpc",
            0,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            null,
            null,
            "OK",
            1);
    AggregateEntry entryWithGrpcError =
        entry(
            "grpc.service/Method",
            "grpc-service",
            "grpc.server",
            null,
            "rpc",
            0,
            false,
            false,
            "client",
            Collections.<UTF8BytesString>emptyList(),
            null,
            null,
            "NOT_FOUND",
            1);
    AggregateEntry entryWithoutGrpc =
        entry(
            "resource",
            "service",
            "operation",
            null,
            "web",
            200,
            false,
            false,
            "server",
            Collections.<UTF8BytesString>emptyList(),
            null,
            null,
            null,
            1);

    List<AggregateEntry> content =
        Arrays.asList(entryWithGrpc, entryWithGrpcError, entryWithoutGrpc);

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    // when
    writer.startBucket(content.size(), startTime, duration);
    for (AggregateEntry aggregateEntry : content) {
      writer.add(aggregateEntry);
    }
    writer.finishBucket();

    // then
    assertTrue(sink.validatedInput());
  }

  @Test
  void additionalMetricTagsEmittedWhenSet() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region", "tenant_id")));
    AggregateTable table = newTable(schema);

    table.findOrInsert(snapshot(schema, "us-east-1", "acme-corp")).recordOneDuration(1L);

    List<AggregateEntry> content = contentOf(table);
    assertEquals(1, content.size());
    // Both configured tags are packed in schema (alphabetical) order: region first, then
    // tenant_id.
    UTF8BytesString[] additionalTags = content.get(0).getAdditionalTags();
    assertEquals(2, additionalTags.length);
    assertEquals("region:us-east-1", additionalTags[0].toString());
    assertEquals("tenant_id:acme-corp", additionalTags[1].toString());

    // ValidatingSink re-checks the serialized AdditionalMetricTags array against the entry.
    serializeAndValidate(content);
  }

  @Test
  void additionalMetricTagsFieldOmittedWhenNoneSet() {
    // Schema configured, but the span doesn't set any of the configured tags.
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region")));
    AggregateTable table = newTable(schema);

    table.findOrInsert(snapshot(schema, new String[] {null})).recordOneDuration(1L);

    List<AggregateEntry> content = contentOf(table);
    assertEquals(1, content.size());
    // No slots populated -> empty packed array -> AdditionalMetricTags omitted from the payload.
    assertEquals(0, content.get(0).getAdditionalTags().length);

    serializeAndValidate(content);
  }

  @Test
  void additionalMetricTagsSkipsNullSlots() {
    AdditionalTagsSchema schema =
        AdditionalTagsSchema.from(new LinkedHashSet<>(Arrays.asList("region", "tenant_id")));
    AggregateTable table = newTable(schema);

    // Set only tenant_id; leave region null.
    table
        .findOrInsert(
            snapshot(
                schema,
                new String[] {
                  /*region*/
                  null, /*tenant_id*/ "acme-corp"
                }))
        .recordOneDuration(1L);

    List<AggregateEntry> content = contentOf(table);
    assertEquals(1, content.size());
    // The null region slot is skipped; only tenant_id survives in the packed array.
    UTF8BytesString[] additionalTags = content.get(0).getAdditionalTags();
    assertEquals(1, additionalTags.length);
    assertEquals("tenant_id:acme-corp", additionalTags[0].toString());

    serializeAndValidate(content);
  }

  // ---------- additional-tags helpers ----------

  private static AggregateTable newTable(AdditionalTagsSchema schema) {
    return new AggregateTable(64, schema);
  }

  private static SpanSnapshot snapshot(AdditionalTagsSchema schema, String... values) {
    String[] padded = new String[schema.size()];
    if (values != null) {
      System.arraycopy(values, 0, padded, 0, Math.min(values.length, padded.length));
    }
    return new SpanSnapshot(
        "resource",
        "service",
        "operation",
        null,
        "web",
        (short) 200,
        false,
        true,
        "client",
        null,
        null,
        null,
        null,
        null,
        padded,
        0L);
  }

  /**
   * Collects the table's entries in iteration order (the same order the writer serializes them).
   */
  private static List<AggregateEntry> contentOf(AggregateTable table) {
    List<AggregateEntry> content = new ArrayList<>();
    table.forEach(content::add);
    return content;
  }

  /**
   * Serializes {@code content} through the shared {@link ValidatingSink} and asserts it decoded.
   */
  private static void serializeAndValidate(List<AggregateEntry> content) {
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    writer.startBucket(content.size(), startTime, duration);
    for (AggregateEntry entry : content) {
      writer.add(entry);
    }
    writer.finishBucket();

    assertTrue(sink.validatedInput());
  }

  static final class ValidatingSink implements Sink {

    private final WellKnownTags wellKnownTags;
    private final long startTimeNanos;
    private final long duration;
    private boolean validated = false;
    private final List<AggregateEntry> content;
    private final String expectedGitCommitSha;

    ValidatingSink(
        WellKnownTags wellKnownTags,
        long startTimeNanos,
        long duration,
        List<AggregateEntry> content) {
      this(wellKnownTags, startTimeNanos, duration, content, null);
    }

    ValidatingSink(
        WellKnownTags wellKnownTags,
        long startTimeNanos,
        long duration,
        List<AggregateEntry> content,
        String expectedGitCommitSha) {
      this.wellKnownTags = wellKnownTags;
      this.startTimeNanos = startTimeNanos;
      this.duration = duration;
      this.content = content;
      this.expectedGitCommitSha = expectedGitCommitSha;
    }

    @Override
    public void register(EventListener listener) {}

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      // Sink.accept can't declare checked exceptions; surface any msgpack decode failure as an
      // assertion error so the test reports it.
      try {
        validate(buffer);
      } catch (IOException e) {
        throw new AssertionError("Failed to decode metric payload", e);
      }
    }

    private void validate(ByteBuffer buffer) throws IOException {
      MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
      int mapSize = unpacker.unpackMapHeader();
      String gitCommitSha = expectedGitCommitSha;
      assertEquals(
          7
              + (Config.get().isExperimentalPropagateProcessTagsEnabled() ? 1 : 0)
              + (gitCommitSha != null ? 1 : 0),
          mapSize);
      assertEquals("RuntimeID", unpacker.unpackString());
      assertEquals(wellKnownTags.getRuntimeId().toString(), unpacker.unpackString());
      assertEquals("Sequence", unpacker.unpackString());
      assertEquals(0L, unpacker.unpackLong());
      assertEquals("Hostname", unpacker.unpackString());
      assertEquals(wellKnownTags.getHostname().toString(), unpacker.unpackString());
      assertEquals("Service", unpacker.unpackString());
      assertEquals(wellKnownTags.getService().toString(), unpacker.unpackString());
      assertEquals("Env", unpacker.unpackString());
      assertEquals(wellKnownTags.getEnv().toString(), unpacker.unpackString());
      assertEquals("Version", unpacker.unpackString());
      assertEquals(wellKnownTags.getVersion().toString(), unpacker.unpackString());
      if (Config.get().isExperimentalPropagateProcessTagsEnabled()) {
        assertEquals("ProcessTags", unpacker.unpackString());
        assertEquals(ProcessTags.getTagsForSerialization().toString(), unpacker.unpackString());
      }
      if (gitCommitSha != null) {
        assertEquals("GitCommitSha", unpacker.unpackString());
        assertEquals(gitCommitSha, unpacker.unpackString());
      }
      assertEquals("Stats", unpacker.unpackString());
      int outerLength = unpacker.unpackArrayHeader();
      assertEquals(1, outerLength);
      assertEquals(3, unpacker.unpackMapHeader());
      assertEquals("Start", unpacker.unpackString());
      assertEquals(startTimeNanos, unpacker.unpackLong());
      assertEquals("Duration", unpacker.unpackString());
      assertEquals(duration, unpacker.unpackLong());
      assertEquals("Stats", unpacker.unpackString());
      int statCount = unpacker.unpackArrayHeader();
      assertEquals(content.size(), statCount);
      for (AggregateEntry entry : content) {
        // counters now live on AggregateEntry
        int metricMapSize = unpacker.unpackMapHeader();
        // Calculate expected map size based on optional fields
        boolean hasHttpMethod = entry.hasHttpMethod();
        boolean hasHttpEndpoint = entry.hasHttpEndpoint();
        boolean hasServiceSource = entry.hasServiceSource();
        boolean hasGrpcStatusCode = entry.hasGrpcStatusCode();
        UTF8BytesString[] additionalTags = entry.getAdditionalTags();
        boolean hasAdditionalTags = additionalTags.length > 0;
        int expectedMapSize =
            15
                + (hasServiceSource ? 1 : 0)
                + (hasHttpMethod ? 1 : 0)
                + (hasHttpEndpoint ? 1 : 0)
                + (hasGrpcStatusCode ? 1 : 0)
                + (hasAdditionalTags ? 1 : 0);
        assertEquals(expectedMapSize, metricMapSize);
        int elementCount = 0;
        assertEquals("Name", unpacker.unpackString());
        assertEquals(entry.getOperationName().toString(), unpacker.unpackString());
        ++elementCount;
        assertEquals("Service", unpacker.unpackString());
        assertEquals(entry.getService().toString(), unpacker.unpackString());
        ++elementCount;
        assertEquals("Resource", unpacker.unpackString());
        assertEquals(entry.getResource().toString(), unpacker.unpackString());
        ++elementCount;
        assertEquals("Type", unpacker.unpackString());
        assertEquals(entry.getType().toString(), unpacker.unpackString());
        ++elementCount;
        assertEquals("HTTPStatusCode", unpacker.unpackString());
        assertEquals(entry.getHttpStatusCode(), unpacker.unpackInt());
        ++elementCount;
        assertEquals("Synthetics", unpacker.unpackString());
        assertEquals(entry.isSynthetics(), unpacker.unpackBoolean());
        ++elementCount;
        assertEquals("IsTraceRoot", unpacker.unpackString());
        assertEquals(
            entry.isTraceRoot() ? TriState.TRUE.serialValue : TriState.FALSE.serialValue,
            unpacker.unpackInt());
        ++elementCount;
        assertEquals("SpanKind", unpacker.unpackString());
        assertEquals(entry.getSpanKind().toString(), unpacker.unpackString());
        ++elementCount;
        assertEquals("PeerTags", unpacker.unpackString());
        int peerTagsLength = unpacker.unpackArrayHeader();
        assertEquals(entry.getPeerTags().size(), peerTagsLength);
        for (int i = 0; i < peerTagsLength; i++) {
          String unpackedPeerTag = unpacker.unpackString();
          assertEquals(entry.getPeerTags().get(i).toString(), unpackedPeerTag);
        }
        ++elementCount;
        // AdditionalMetricTags is a packed array of pre-built "key:value" strings in schema
        // (alphabetical-by-key) order. Emitted immediately after PeerTags and omitted entirely
        // when the entry set none, mirroring the writer's optional-field handling above.
        if (hasAdditionalTags) {
          assertEquals("AdditionalMetricTags", unpacker.unpackString());
          int additionalTagsLength = unpacker.unpackArrayHeader();
          assertEquals(additionalTags.length, additionalTagsLength);
          for (int i = 0; i < additionalTagsLength; i++) {
            assertEquals(additionalTags[i].toString(), unpacker.unpackString());
          }
          ++elementCount;
        }
        // Service source is only present when the service name has been overridden by the tracer
        if (hasServiceSource) {
          assertEquals("srv_src", unpacker.unpackString());
          assertEquals(entry.getServiceSource().toString(), unpacker.unpackString());
          ++elementCount;
        }
        // HTTPMethod and HTTPEndpoint are optional - only present if non-null
        if (hasHttpMethod) {
          assertEquals("HTTPMethod", unpacker.unpackString());
          assertEquals(entry.getHttpMethod().toString(), unpacker.unpackString());
          ++elementCount;
        }
        if (hasHttpEndpoint) {
          assertEquals("HTTPEndpoint", unpacker.unpackString());
          assertEquals(entry.getHttpEndpoint().toString(), unpacker.unpackString());
          ++elementCount;
        }
        if (hasGrpcStatusCode) {
          assertEquals("GRPCStatusCode", unpacker.unpackString());
          assertEquals(entry.getGrpcStatusCode().toString(), unpacker.unpackString());
          ++elementCount;
        }
        assertEquals("Hits", unpacker.unpackString());
        assertEquals(entry.getHitCount(), unpacker.unpackInt());
        ++elementCount;
        assertEquals("Errors", unpacker.unpackString());
        assertEquals(entry.getErrorCount(), unpacker.unpackInt());
        ++elementCount;
        assertEquals("TopLevelHits", unpacker.unpackString());
        assertEquals(entry.getTopLevelCount(), unpacker.unpackInt());
        ++elementCount;
        assertEquals("Duration", unpacker.unpackString());
        assertEquals(entry.getDuration(), unpacker.unpackLong());
        ++elementCount;
        assertEquals("OkSummary", unpacker.unpackString());
        validateSketch(unpacker);
        ++elementCount;
        assertEquals("ErrorSummary", unpacker.unpackString());
        validateSketch(unpacker);
        ++elementCount;
        assertEquals(metricMapSize, elementCount);
      }
      validated = true;
    }

    private void validateSketch(MessageUnpacker unpacker) throws IOException {
      int length = unpacker.unpackBinaryHeader();
      assertTrue(length > 0);
      unpacker.readPayload(length);
    }

    boolean validatedInput() {
      return validated;
    }
  }
}
