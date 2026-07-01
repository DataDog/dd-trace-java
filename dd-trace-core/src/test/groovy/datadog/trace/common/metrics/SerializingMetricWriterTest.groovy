package datadog.trace.common.metrics

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static java.util.concurrent.TimeUnit.SECONDS

import datadog.metrics.api.Histograms
import datadog.metrics.impl.DDSketchHistograms
import datadog.trace.api.Config
import datadog.trace.api.ProcessTags
import datadog.trace.api.WellKnownTags
import datadog.trace.api.git.CommitInfo
import datadog.trace.api.git.GitInfo
import datadog.trace.api.git.GitInfoProvider
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString
import datadog.trace.test.util.DDSpecification
import java.nio.ByteBuffer
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageUnpacker

class SerializingMetricWriterTest extends DDSpecification {

  def setupSpec() {
    Histograms.register(DDSketchHistograms.FACTORY)
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
    AggregateEntry e = AggregateEntryTestUtils.of(
      resource, service, operationName, serviceSource, type,
      httpStatusCode, synthetic, traceRoot, spanKind, peerTags,
      httpMethod, httpEndpoint, grpcStatusCode)
    hitCount.times { e.recordOneDuration(1L) }
    return e
  }

  def "should produce correct message #iterationIndex with process tags enabled #withProcessTags" () {
    setup:
    if (!withProcessTags) {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    }
    ProcessTags.reset()
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis())
    long duration = SECONDS.toNanos(10)
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version","language")
    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content)
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128)

    when:
    writer.startBucket(content.size(), startTime, duration)
    for (AggregateEntry e : content) {
      writer.add(e)
    }
    writer.finishBucket()

    then:
    sink.validatedInput()

    cleanup:
    removeSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED)
    ProcessTags.reset()

    where:
    content << [
      [
        entry(
        "resource1", "service1", "operation1", null, "type", 0,
        false, false, "client",
        [
          UTF8BytesString.create("country:canada"),
          UTF8BytesString.create("georegion:amer"),
          UTF8BytesString.create("peer.service:remote-service")
        ],
        null, null, null,
        10),
        entry(
        "resource2", "service2", "operation2", null, "type2", 200,
        true, false, "producer",
        [
          UTF8BytesString.create("country:canada"),
          UTF8BytesString.create("georegion:amer"),
          UTF8BytesString.create("peer.service:remote-service")
        ],
        null, null, null,
        9),
        entry(
        "GET /api/users/:id", "web-service", "http.request", null, "web", 200,
        false, true, "server",
        [],
        null, null, null,
        5)
      ],
      (0..10000).collect({ i ->
        entry(
          "resource" + i, "service" + i, "operation" + i, null, "type", 0,
          false, false, "producer",
          [UTF8BytesString.create("messaging.destination:dest" + i)],
          null, null, null,
          10)
      })
    ]
    withProcessTags << [true, false]
  }

  def "ServiceSource optional in the payload"() {
    setup:
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis())
    long duration = SECONDS.toNanos(10)
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language")

    def entryNoSource = entry("resource", "service", "operation", null, "type", 200, false, false, "server", [], "GET", "/api/users", null, 1)
    def entryWithSource = entry("resource", "service", "operation", "source", "type", 200, false, false, "server", [], "POST", null, null, 1)

    def content = [entryNoSource, entryWithSource]

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content)
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128)

    when:
    writer.startBucket(content.size(), startTime, duration)
    for (AggregateEntry e : content) {
      writer.add(e)
    }
    writer.finishBucket()

    then:
    sink.validatedInput()
  }

  def "HTTPMethod and HTTPEndpoint fields are optional in payload"() {
    setup:
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis())
    long duration = SECONDS.toNanos(10)
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language")

    def entryWithBoth = entry("resource", "service", "operation", null, "type", 200, false, false, "server", [], "GET", "/api/users", null, 1)
    def entryWithMethodOnly = entry("resource", "service", "operation", null, "type", 200, false, false, "server", [], "POST", null, null, 1)
    def entryWithEndpointOnly = entry("resource", "service", "operation", null, "type", 200, false, false, "server", [], null, "/api/orders", null, 1)
    def entryWithNeither = entry("resource", "service", "operation", null, "type", 200, false, false, "client", [], null, null, null, 1)

    def content = [entryWithBoth, entryWithMethodOnly, entryWithEndpointOnly, entryWithNeither]

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content)
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128)

    when:
    writer.startBucket(content.size(), startTime, duration)
    for (AggregateEntry e : content) {
      writer.add(e)
    }
    writer.finishBucket()

    then:
    sink.validatedInput()
  }

  def "add git sha commit info when sha commit is #shaCommit"() {
    setup:
    GitInfoProvider gitInfoProvider = Mock(GitInfoProvider)
    gitInfoProvider.getGitInfo() >> new GitInfo(null, null, null, new CommitInfo(shaCommit))

    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis())
    long duration = SECONDS.toNanos(10)
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language")

    def e = entry("resource", "service", "operation", null, "type", 200, false, false, "server", [], "GET", "/api/users", null, 1)

    def content = [e]

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content, shaCommit)
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128, gitInfoProvider)

    when:
    writer.startBucket(content.size(), startTime, duration)
    for (AggregateEntry entryItem : content) {
      writer.add(entryItem)
    }
    writer.finishBucket()

    then:
    sink.validatedInput()

    where:
    shaCommit << [null, "123456"]
  }

  def "GRPCStatusCode field is present in payload for rpc-type spans"() {
    setup:
    long startTime = MILLISECONDS.toNanos(System.currentTimeMillis())
    long duration = SECONDS.toNanos(10)
    WellKnownTags wellKnownTags = new WellKnownTags("runtimeid", "hostname", "env", "service", "version", "language")

    def entryWithGrpc = entry("grpc.service/Method", "grpc-service", "grpc.server", null, "rpc", 0, false, false, "server", [], null, null, "OK", 1)
    def entryWithGrpcError = entry("grpc.service/Method", "grpc-service", "grpc.server", null, "rpc", 0, false, false, "client", [], null, null, "NOT_FOUND", 1)
    def entryWithoutGrpc = entry("resource", "service", "operation", null, "web", 200, false, false, "server", [], null, null, null, 1)

    def content = [entryWithGrpc, entryWithGrpcError, entryWithoutGrpc]

    ValidatingSink sink = new ValidatingSink(wellKnownTags, startTime, duration, content)
    SerializingMetricWriter writer = new SerializingMetricWriter(wellKnownTags, sink, 128)

    when:
    writer.startBucket(content.size(), startTime, duration)
    for (AggregateEntry e : content) {
      writer.add(e)
    }
    writer.finishBucket()

    then:
    sink.validatedInput()
  }

  static class ValidatingSink implements Sink {

    private final WellKnownTags wellKnownTags
    private final long startTimeNanos
    private final long duration
    private boolean validated = false
    private List<AggregateEntry> content
    private final String expectedGitCommitSha

    ValidatingSink(WellKnownTags wellKnownTags, long startTimeNanos, long duration,
    List<AggregateEntry> content, String expectedGitCommitSha = null) {
      this.wellKnownTags = wellKnownTags
      this.startTimeNanos = startTimeNanos
      this.duration = duration
      this.content = content
      this.expectedGitCommitSha = expectedGitCommitSha
    }

    @Override
    void register(EventListener listener) {
    }

    @Override
    void accept(int messageCount, ByteBuffer buffer) {
      MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(buffer)
      int mapSize = unpacker.unpackMapHeader()
      String gitCommitSha = expectedGitCommitSha
      assert mapSize == (7 + (Config.get().isExperimentalPropagateProcessTagsEnabled() ? 1 : 0)
      + (gitCommitSha != null ? 1 : 0))
      assert unpacker.unpackString() == "RuntimeID"
      assert unpacker.unpackString() == wellKnownTags.getRuntimeId() as String
      assert unpacker.unpackString() == "Sequence"
      assert unpacker.unpackLong() == 0L
      assert unpacker.unpackString() == "Hostname"
      assert unpacker.unpackString() == wellKnownTags.getHostname() as String
      assert unpacker.unpackString() == "Service"
      assert unpacker.unpackString() == wellKnownTags.getService() as String
      assert unpacker.unpackString() == "Env"
      assert unpacker.unpackString() == wellKnownTags.getEnv() as String
      assert unpacker.unpackString() == "Version"
      assert unpacker.unpackString() == wellKnownTags.getVersion() as String
      if (Config.get().isExperimentalPropagateProcessTagsEnabled()) {
        assert unpacker.unpackString() == "ProcessTags"
        assert unpacker.unpackString() == ProcessTags.tagsForSerialization as String
      }
      if (gitCommitSha != null) {
        assert unpacker.unpackString() == "GitCommitSha"
        assert unpacker.unpackString() == gitCommitSha
      }
      assert unpacker.unpackString() == "Stats"
      int outerLength = unpacker.unpackArrayHeader()
      assert outerLength == 1
      assert unpacker.unpackMapHeader() == 3
      assert unpacker.unpackString() == "Start"
      assert unpacker.unpackLong() == startTimeNanos
      assert unpacker.unpackString() == "Duration"
      assert unpacker.unpackLong() == duration
      assert unpacker.unpackString() == "Stats"
      int statCount = unpacker.unpackArrayHeader()
      assert statCount == content.size()
      for (AggregateEntry entry : content) {
        // counters now live on AggregateEntry
        int metricMapSize = unpacker.unpackMapHeader()
        // Calculate expected map size based on optional fields
        boolean hasHttpMethod = entry.hasHttpMethod()
        boolean hasHttpEndpoint = entry.hasHttpEndpoint()
        boolean hasServiceSource = entry.hasServiceSource()
        boolean hasGrpcStatusCode = entry.hasGrpcStatusCode()
        int expectedMapSize = 15 + (hasServiceSource ? 1 : 0) + (hasHttpMethod ? 1 : 0) + (hasHttpEndpoint ? 1 : 0) + (hasGrpcStatusCode ? 1 : 0)
        assert metricMapSize == expectedMapSize
        int elementCount = 0
        assert unpacker.unpackString() == "Name"
        assert unpacker.unpackString() == entry.getOperationName() as String
        ++elementCount
        assert unpacker.unpackString() == "Service"
        assert unpacker.unpackString() == entry.getService() as String
        ++elementCount
        assert unpacker.unpackString() == "Resource"
        assert unpacker.unpackString() == entry.getResource() as String
        ++elementCount
        assert unpacker.unpackString() == "Type"
        assert unpacker.unpackString() == entry.getType() as String
        ++elementCount
        assert unpacker.unpackString() == "HTTPStatusCode"
        assert unpacker.unpackInt() == entry.getHttpStatusCode()
        ++elementCount
        assert unpacker.unpackString() == "Synthetics"
        assert unpacker.unpackBoolean() == entry.isSynthetics()
        ++elementCount
        assert unpacker.unpackString() == "IsTraceRoot"
        assert unpacker.unpackInt() == (entry.isTraceRoot() ? TriState.TRUE.serialValue : TriState.FALSE.serialValue)
        ++elementCount
        assert unpacker.unpackString() == "SpanKind"
        assert unpacker.unpackString() == entry.getSpanKind() as String
        ++elementCount
        assert unpacker.unpackString() == "PeerTags"
        int peerTagsLength = unpacker.unpackArrayHeader()
        assert peerTagsLength == entry.getPeerTags().size()
        for (int i = 0; i < peerTagsLength; i++) {
          def unpackedPeerTag = unpacker.unpackString()
          assert unpackedPeerTag == entry.getPeerTags()[i].toString()
        }
        ++elementCount
        // Service source is only present when the service name has been overridden by the tracer
        if (hasServiceSource) {
          assert unpacker.unpackString() == "srv_src"
          assert unpacker.unpackString() == entry.getServiceSource().toString()
          ++elementCount
        }
        // HTTPMethod and HTTPEndpoint are optional - only present if non-null
        if (hasHttpMethod) {
          assert unpacker.unpackString() == "HTTPMethod"
          assert unpacker.unpackString() == entry.getHttpMethod() as String
          ++elementCount
        }
        if (hasHttpEndpoint) {
          assert unpacker.unpackString() == "HTTPEndpoint"
          assert unpacker.unpackString() == entry.getHttpEndpoint() as String
          ++elementCount
        }
        if (hasGrpcStatusCode) {
          assert unpacker.unpackString() == "GRPCStatusCode"
          assert unpacker.unpackString() == entry.getGrpcStatusCode() as String
          ++elementCount
        }
        assert unpacker.unpackString() == "Hits"
        assert unpacker.unpackInt() == entry.getHitCount()
        ++elementCount
        assert unpacker.unpackString() == "Errors"
        assert unpacker.unpackInt() == entry.getErrorCount()
        ++elementCount
        assert unpacker.unpackString() == "TopLevelHits"
        assert unpacker.unpackInt() == entry.getTopLevelCount()
        ++elementCount
        assert unpacker.unpackString() == "Duration"
        assert unpacker.unpackLong() == entry.getDuration()
        ++elementCount
        assert unpacker.unpackString() == "OkSummary"
        validateSketch(unpacker)
        ++elementCount
        assert unpacker.unpackString() == "ErrorSummary"
        validateSketch(unpacker)
        ++elementCount
        assert elementCount == metricMapSize
      }
      validated = true
    }

    private void validateSketch(MessageUnpacker unpacker) {
      int length = unpacker.unpackBinaryHeader()
      assert length > 0
      unpacker.readPayload(length)
    }

    boolean validatedInput() {
      return validated
    }
  }
}
