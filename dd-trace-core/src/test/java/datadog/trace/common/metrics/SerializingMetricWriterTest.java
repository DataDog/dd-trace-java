package datadog.trace.common.metrics;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.api.Config;
import datadog.trace.api.Pair;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.test.DDCoreSpecification;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

class SerializingMetricWriterTest extends DDCoreSpecification {

  @BeforeAll
  static void setupSpec() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  static Stream<Arguments> shouldProduceCorrectMessageArguments() {
    List<Pair<MetricKey, AggregateMetric>> content1 = new ArrayList<>();
    content1.add(
        Pair.of(
            new MetricKey(
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
                null),
            new AggregateMetric().recordDurations(10, new AtomicLongArray(new long[] {1L}))));
    content1.add(
        Pair.of(
            new MetricKey(
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
                null),
            new AggregateMetric().recordDurations(9, new AtomicLongArray(new long[] {1L}))));
    content1.add(
        Pair.of(
            new MetricKey(
                "GET /api/users/:id",
                "web-service",
                "http.request",
                null,
                "web",
                200,
                false,
                true,
                "server",
                Collections.emptyList(),
                "GET",
                "/api/users/:id",
                null),
            new AggregateMetric().recordDurations(5, new AtomicLongArray(new long[] {1L}))));

    List<Pair<MetricKey, AggregateMetric>> content2 = new ArrayList<>();
    for (int i = 0; i <= 10000; i++) {
      content2.add(
          Pair.of(
              new MetricKey(
                  "resource" + i,
                  "service" + i,
                  "operation" + i,
                  null,
                  "type",
                  0,
                  false,
                  false,
                  "producer",
                  Collections.singletonList(
                      UTF8BytesString.create("messaging.destination:dest" + i)),
                  null,
                  null,
                  null),
              new AggregateMetric().recordDurations(10, new AtomicLongArray(new long[] {1L}))));
    }

    return Stream.of(Arguments.of(content1, true), Arguments.of(content2, false));
  }

  @ParameterizedTest
  @MethodSource("shouldProduceCorrectMessageArguments")
  void shouldProduceCorrectMessage(
      List<Pair<MetricKey, AggregateMetric>> content, boolean withProcessTags) throws Exception {
    if (!withProcessTags) {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false");
    }
    ProcessTags.reset(Config.get());
    try {
      long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
      long duration = SECONDS.toNanos(10);
      WellKnownTags wellKnownTags =
          new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");
      ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
      SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

      writer.startBucket(content.size(), startTime, duration);
      for (Pair<MetricKey, AggregateMetric> pair : content) {
        writer.add(pair.getLeft(), pair.getRight());
      }
      writer.finishBucket();

      assertTrue(sink.validatedInput());
    } finally {
      removeSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED);
      ProcessTags.reset(Config.get());
    }
  }

  @Test
  void serviceSourceOptionalInThePayload() throws Exception {
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    MetricKey keyWithNoSource =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/users",
            null);
    MetricKey keyWithSource =
        new MetricKey(
            "resource",
            "service",
            "operation",
            "source",
            "type",
            200,
            false,
            false,
            "server",
            Collections.emptyList(),
            "POST",
            null,
            null);

    List<Pair<MetricKey, AggregateMetric>> content = new ArrayList<>();
    content.add(
        Pair.of(
            keyWithNoSource,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));
    content.add(
        Pair.of(
            keyWithSource,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    writer.startBucket(content.size(), startTime, duration);
    for (Pair<MetricKey, AggregateMetric> pair : content) {
      writer.add(pair.getLeft(), pair.getRight());
    }
    writer.finishBucket();

    assertTrue(sink.validatedInput());
  }

  @Test
  void httpMethodAndHttpEndpointFieldsAreOptionalInPayload() throws Exception {
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    MetricKey keyWithBoth =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/users",
            null);
    MetricKey keyWithMethodOnly =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.emptyList(),
            "POST",
            null,
            null);
    MetricKey keyWithEndpointOnly =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.emptyList(),
            null,
            "/api/orders",
            null);
    MetricKey keyWithNeither =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "client",
            Collections.emptyList(),
            null,
            null,
            null);

    List<Pair<MetricKey, AggregateMetric>> content = new ArrayList<>();
    content.add(
        Pair.of(
            keyWithBoth,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));
    content.add(
        Pair.of(
            keyWithMethodOnly,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));
    content.add(
        Pair.of(
            keyWithEndpointOnly,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));
    content.add(
        Pair.of(
            keyWithNeither,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    writer.startBucket(content.size(), startTime, duration);
    for (Pair<MetricKey, AggregateMetric> pair : content) {
      writer.add(pair.getLeft(), pair.getRight());
    }
    writer.finishBucket();

    assertTrue(sink.validatedInput());
  }

  @ParameterizedTest
  @ValueSource(strings = {"123456"})
  @NullSource
  void addGitShaCommitInfoWhenShaCommitIs(String shaCommit) throws Exception {
    ProcessTags.reset(Config.get());
    GitInfoProvider gitInfoProvider = Mockito.mock(GitInfoProvider.class);
    Mockito.when(gitInfoProvider.getGitInfo())
        .thenReturn(new GitInfo(null, null, null, new CommitInfo(shaCommit)));

    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    MetricKey key =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "type",
            200,
            false,
            false,
            "server",
            Collections.emptyList(),
            "GET",
            "/api/users",
            null);

    List<Pair<MetricKey, AggregateMetric>> content =
        Collections.singletonList(
            Pair.of(
                key,
                new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));

    ValidatingSink sink =
        new ValidatingSink(wellKnownTags, startTime, duration, content, shaCommit);
    SerializingMetricWriter writer =
        new SerializingMetricWriter(wellKnownTags, sink, 128, gitInfoProvider);

    writer.startBucket(content.size(), startTime, duration);
    for (Pair<MetricKey, AggregateMetric> pair : content) {
      writer.add(pair.getLeft(), pair.getRight());
    }
    writer.finishBucket();

    assertTrue(sink.validatedInput());
  }

  @Test
  void grpcStatusCodeFieldIsPresentInPayloadForRpcTypeSpans() throws Exception {
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis());
    long duration = SECONDS.toNanos(10);
    WellKnownTags wellKnownTags =
        new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language");

    MetricKey keyWithGrpc =
        new MetricKey(
            "grpc.service/Method",
            "grpc-service",
            "grpc.server",
            null,
            "rpc",
            0,
            false,
            false,
            "server",
            Collections.emptyList(),
            null,
            null,
            "OK");
    MetricKey keyWithGrpcError =
        new MetricKey(
            "grpc.service/Method",
            "grpc-service",
            "grpc.server",
            null,
            "rpc",
            0,
            false,
            false,
            "client",
            Collections.emptyList(),
            null,
            null,
            "NOT_FOUND");
    MetricKey keyWithoutGrpc =
        new MetricKey(
            "resource",
            "service",
            "operation",
            null,
            "web",
            200,
            false,
            false,
            "server",
            Collections.emptyList(),
            null,
            null,
            null);

    List<Pair<MetricKey, AggregateMetric>> content = new ArrayList<>();
    content.add(
        Pair.of(
            keyWithGrpc,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));
    content.add(
        Pair.of(
            keyWithGrpcError,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));
    content.add(
        Pair.of(
            keyWithoutGrpc,
            new AggregateMetric().recordDurations(1, new AtomicLongArray(new long[] {1L}))));

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content);
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128);

    writer.startBucket(content.size(), startTime, duration);
    for (Pair<MetricKey, AggregateMetric> pair : content) {
      writer.add(pair.getLeft(), pair.getRight());
    }
    writer.finishBucket();

    assertTrue(sink.validatedInput());
  }

  static class ValidatingSink implements Sink {

    private final WellKnownTags wellKnownTags;
    private final long startTimeNanos;
    private final long duration;
    private boolean validated = false;
    private List<Pair<MetricKey, AggregateMetric>> content;
    private final String expectedGitCommitSha;
    private final boolean useExplicitSha;

    ValidatingSink(
        WellKnownTags wellKnownTags,
        long startTimeNanos,
        long duration,
        List<Pair<MetricKey, AggregateMetric>> content) {
      this.wellKnownTags = wellKnownTags;
      this.startTimeNanos = startTimeNanos;
      this.duration = duration;
      this.content = content;
      this.expectedGitCommitSha = null;
      this.useExplicitSha = false;
    }

    ValidatingSink(
        WellKnownTags wellKnownTags,
        long startTimeNanos,
        long duration,
        List<Pair<MetricKey, AggregateMetric>> content,
        String expectedGitCommitSha) {
      this.wellKnownTags = wellKnownTags;
      this.startTimeNanos = startTimeNanos;
      this.duration = duration;
      this.content = content;
      this.expectedGitCommitSha = expectedGitCommitSha;
      this.useExplicitSha = true;
    }

    @Override
    public void register(EventListener listener) {}

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      try {
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer);
        int mapSize = unpacker.unpackMapHeader();
        String gitCommitSha;
        if (useExplicitSha) {
          gitCommitSha = expectedGitCommitSha;
        } else {
          gitCommitSha = null;
          try {
            GitInfo gitInfo = GitInfoProvider.INSTANCE.getGitInfo();
            if (gitInfo != null && gitInfo.getCommit() != null) {
              gitCommitSha = gitInfo.getCommit().getSha();
            }
          } catch (Exception e) {
            // ignore
          }
        }
        UTF8BytesString processTags = ProcessTags.getTagsForSerialization();
        assertEquals(7 + (processTags != null ? 1 : 0) + (gitCommitSha != null ? 1 : 0), mapSize);
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
        if (processTags != null) {
          assertEquals("ProcessTags", unpacker.unpackString());
          assertEquals(processTags.toString(), unpacker.unpackString());
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
        for (Pair<MetricKey, AggregateMetric> pair : content) {
          MetricKey key = pair.getLeft();
          AggregateMetric value = pair.getRight();
          int metricMapSize = unpacker.unpackMapHeader();
          boolean hasHttpMethod = key.getHttpMethod() != null;
          boolean hasHttpEndpoint = key.getHttpEndpoint() != null;
          boolean hasServiceSource = key.getServiceSource() != null;
          boolean hasGrpcStatusCode = key.getGrpcStatusCode() != null;
          int expectedMapSize =
              15
                  + (hasServiceSource ? 1 : 0)
                  + (hasHttpMethod ? 1 : 0)
                  + (hasHttpEndpoint ? 1 : 0)
                  + (hasGrpcStatusCode ? 1 : 0);
          assertEquals(expectedMapSize, metricMapSize);
          int elementCount = 0;
          assertEquals("Name", unpacker.unpackString());
          assertEquals(key.getOperationName().toString(), unpacker.unpackString());
          ++elementCount;
          assertEquals("Service", unpacker.unpackString());
          assertEquals(key.getService().toString(), unpacker.unpackString());
          ++elementCount;
          assertEquals("Resource", unpacker.unpackString());
          assertEquals(key.getResource().toString(), unpacker.unpackString());
          ++elementCount;
          assertEquals("Type", unpacker.unpackString());
          assertEquals(key.getType().toString(), unpacker.unpackString());
          ++elementCount;
          assertEquals("HTTPStatusCode", unpacker.unpackString());
          assertEquals(key.getHttpStatusCode(), unpacker.unpackInt());
          ++elementCount;
          assertEquals("Synthetics", unpacker.unpackString());
          assertEquals(key.isSynthetics(), unpacker.unpackBoolean());
          ++elementCount;
          assertEquals("IsTraceRoot", unpacker.unpackString());
          assertEquals(
              key.isTraceRoot() ? TriState.TRUE.serialValue : TriState.FALSE.serialValue,
              unpacker.unpackInt());
          ++elementCount;
          assertEquals("SpanKind", unpacker.unpackString());
          assertEquals(key.getSpanKind().toString(), unpacker.unpackString());
          ++elementCount;
          assertEquals("PeerTags", unpacker.unpackString());
          int peerTagsLength = unpacker.unpackArrayHeader();
          assertEquals(key.getPeerTags().size(), peerTagsLength);
          for (int i = 0; i < peerTagsLength; i++) {
            String unpackedPeerTag = unpacker.unpackString();
            assertEquals(key.getPeerTags().get(i).toString(), unpackedPeerTag);
          }
          ++elementCount;
          if (hasServiceSource) {
            assertEquals("srv_src", unpacker.unpackString());
            assertEquals(key.getServiceSource().toString(), unpacker.unpackString());
            ++elementCount;
          }
          if (hasHttpMethod) {
            assertEquals("HTTPMethod", unpacker.unpackString());
            assertEquals(key.getHttpMethod().toString(), unpacker.unpackString());
            ++elementCount;
          }
          if (hasHttpEndpoint) {
            assertEquals("HTTPEndpoint", unpacker.unpackString());
            assertEquals(key.getHttpEndpoint().toString(), unpacker.unpackString());
            ++elementCount;
          }
          if (hasGrpcStatusCode) {
            assertEquals("GRPCStatusCode", unpacker.unpackString());
            assertEquals(key.getGrpcStatusCode().toString(), unpacker.unpackString());
            ++elementCount;
          }
          assertEquals("Hits", unpacker.unpackString());
          assertEquals(value.getHitCount(), unpacker.unpackInt());
          ++elementCount;
          assertEquals("Errors", unpacker.unpackString());
          assertEquals(value.getErrorCount(), unpacker.unpackInt());
          ++elementCount;
          assertEquals("TopLevelHits", unpacker.unpackString());
          assertEquals(value.getTopLevelCount(), unpacker.unpackInt());
          ++elementCount;
          assertEquals("Duration", unpacker.unpackString());
          assertEquals(value.getDuration(), unpacker.unpackLong());
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
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private void validateSketch(MessageUnpacker unpacker) throws Exception {
      int length = unpacker.unpackBinaryHeader();
      assertTrue(length > 0);
      unpacker.readPayload(length);
    }

    boolean validatedInput() {
      return validated;
    }
  }
}
