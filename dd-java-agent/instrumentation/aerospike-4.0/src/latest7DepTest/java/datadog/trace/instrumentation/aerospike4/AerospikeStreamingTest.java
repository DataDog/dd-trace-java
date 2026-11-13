package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNonNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testcontainers.containers.wait.strategy.Wait.forLogMessage;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.async.EventLoop;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.listener.RecordSequenceListener;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.github.dockerjava.api.model.Ulimit;
import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TraceAssertions;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

abstract class AerospikeStreamingTest extends AbstractInstrumentationTest {
  private Fixture fixture;

  abstract int version();

  @BeforeEach
  void setUpAerospike() {
    fixture = new Fixture();
    fixture.start();
  }

  @AfterEach
  void closeAerospike() {
    if (fixture != null) fixture.close();
  }

  @Test
  void scanRecordsRetainOperationContextAfterCallerCloses() throws Exception {
    fixture.assertScanPreservesContext();
    assertTraces(TraceAssertions.SORT_BY_START_TIME, fixture.expectedTraces(version()));
  }

  private static final class Fixture {
    private final GenericContainer<?> server =
        new GenericContainer<>(DockerImageName.parse(System.getProperty("test.aerospike.image")))
            .withExposedPorts(3000)
            .withCreateContainerCmdModifier(cmd ->
                cmd.getHostConfig()
                    .withUlimits(new Ulimit[] {new Ulimit("nofile", 15000L, 15000L)}))
            .waitingFor(forLogMessage(".*heartbeat-received.*\\n", 1));
    private NioEventLoops eventLoops;
    private AerospikeClient client;

    void start() {
      server.start();
      eventLoops = new NioEventLoops(1);
      ClientPolicy policy = new ClientPolicy();
      policy.eventLoops = eventLoops;
      client = new AerospikeClient(policy, "localhost", server.getMappedPort(3000));
    }

    void close() {
      try {
        if (client != null) client.close();
      } finally {
        try {
          if (eventLoops != null) eventLoops.close();
        } finally {
          server.close();
        }
      }
    }

    void assertScanPreservesContext() throws Exception {
      String setName = "stream_" + UUID.randomUUID();
      for (int i = 1; i <= 3; i++) {
        client.put(null, new Key("test", setName, "key-" + i), new Bin("name", "value-" + i));
      }
      EventLoop eventLoop = eventLoops.next();
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      AtomicBoolean callerClosed = new AtomicBoolean();
      AtomicInteger records = new AtomicInteger();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      AtomicReference<AgentSpan> operation = new AtomicReference<>();
      CompletableFuture<Void> completed = new CompletableFuture<>();
      Set<String> received = new HashSet<>();
      ContextKey<Object> markerKey = ContextKey.named("aerospike-real-stream");
      Object marker = new Object();
      RecordSequenceListener listener =
          new RecordSequenceListener() {
            @Override
            public void onRecord(Key key, Record record) {
              try {
                assertTrue(callerClosed.get());
                assertEquals(
                    "AerospikeClient.scanAll", String.valueOf(activeSpan().getResourceName()));
                operation.compareAndSet(null, activeSpan());
                assertSame(operation.get(), activeSpan());
                assertSame(marker, Context.current().get(markerKey));
                assertTrue(received.add(record.getString("name")));
                records.incrementAndGet();
                AgentSpan child = startSpan("test", "scan record");
                child.finish();
              } catch (Throwable error) {
                failure.compareAndSet(null, error);
              }
            }

            @Override
            public void onSuccess() {
              completed.complete(null);
            }

            @Override
            public void onFailure(AerospikeException error) {
              completed.completeExceptionally(error);
            }
          };
      try {
        eventLoop.execute(() -> {
          entered.countDown();
          try {
            assertTrue(release.await(10, SECONDS), "gate timed out");
          } catch (Throwable error) {
            failure.compareAndSet(null, error);
          }
        });
        assertTrue(entered.await(10, SECONDS));
        AgentSpan parent = startSpan("test", "scan caller");
        try (ContextScope caller = parent.with(markerKey, marker).attach()) {
          client.scanAll(eventLoop, listener, new ScanPolicy(), "test", setName, "name");
        } finally {
          parent.finish();
          callerClosed.set(true);
          release.countDown();
        }
        assertNull(activeSpan());
        completed.get(10, SECONDS);
        if (failure.get() != null) throw new AssertionError("scan callback failed", failure.get());
        assertEquals(3, records.get());
        assertEquals(new HashSet<>(Arrays.asList("value-1", "value-2", "value-3")), received);
      } finally {
        release.countDown();
      }
    }

    TraceMatcher[] expectedTraces(int version) {
      SpanMatcher put =
          aerospikeSpan(version, "AerospikeClient.put")
              .root()
              .topLevel()
              .tags(
                  defaultTags(),
                  tag(Tags.COMPONENT, matches(Pattern.compile(Pattern.quote("java-aerospike")))),
                  tag(
                      Tags.SPAN_KIND,
                      matches(Pattern.compile(Pattern.quote(Tags.SPAN_KIND_CLIENT)))),
                  tag(Tags.DB_TYPE, matches(Pattern.compile(Pattern.quote("aerospike")))),
                  tag(Tags.DB_INSTANCE, matches(Pattern.compile(Pattern.quote("test")))),
                  tag(Tags.PEER_HOSTNAME, matches(Pattern.compile(Pattern.quote("localhost")))),
                  tag(Tags.PEER_HOST_IPV4, matches(Pattern.compile(Pattern.quote("127.0.0.1")))),
                  tag(Tags.PEER_PORT, is(server.getMappedPort(3000))),
                  tag(
                      Tags.PEER_SERVICE,
                      version == 0 ? isNull() : matches(Pattern.compile(Pattern.quote("test")))),
                  tag(
                      "_dd.peer.service.source",
                      version == 0
                          ? isNull()
                          : matches(Pattern.compile(Pattern.quote(Tags.DB_INSTANCE)))),
                  tag("_dd.svc_src", version == 0 ? isNonNull() : isNull()));
      return new TraceMatcher[] {
        trace(put),
        trace(put),
        trace(put),
        trace(
            TraceMatcher.SORT_BY_START_TIME,
            span().root().operationName("scan caller").tags(defaultTags()),
            aerospikeSpan(version, "AerospikeClient.scanAll")
                .childOfIndex(0)
                .topLevel(version == 0)
                .tags(
                    defaultTags(),
                    tag(Tags.COMPONENT, matches(Pattern.compile(Pattern.quote("java-aerospike")))),
                    tag(
                        Tags.SPAN_KIND,
                        matches(Pattern.compile(Pattern.quote(Tags.SPAN_KIND_CLIENT)))),
                    tag(Tags.DB_TYPE, matches(Pattern.compile(Pattern.quote("aerospike")))),
                    tag(Tags.DB_INSTANCE, matches(Pattern.compile(Pattern.quote("test")))),
                    tag(Tags.PEER_HOSTNAME, matches(Pattern.compile(Pattern.quote("localhost")))),
                    tag(Tags.PEER_HOST_IPV4, matches(Pattern.compile(Pattern.quote("127.0.0.1")))),
                    tag(Tags.PEER_PORT, is(server.getMappedPort(3000))),
                    tag(
                        Tags.PEER_SERVICE,
                        version == 0 ? isNull() : matches(Pattern.compile(Pattern.quote("test")))),
                    tag(
                        "_dd.peer.service.source",
                        version == 0
                            ? isNull()
                            : matches(Pattern.compile(Pattern.quote(Tags.DB_INSTANCE)))),
                    tag("_dd.svc_src", version == 0 ? isNonNull() : isNull())),
            span()
                .childOfIndex(1)
                .operationName("scan record")
                .tags(defaultTags(), tag("_dd.svc_src", version == 0 ? isNonNull() : isNull())),
            span()
                .childOfIndex(1)
                .operationName("scan record")
                .tags(defaultTags(), tag("_dd.svc_src", version == 0 ? isNonNull() : isNull())),
            span()
                .childOfIndex(1)
                .operationName("scan record")
                .tags(defaultTags(), tag("_dd.svc_src", version == 0 ? isNonNull() : isNull())))
      };
    }

    private SpanMatcher aerospikeSpan(int version, String method) {
      return span()
          .serviceName(version == 0 ? "aerospike" : Config.get().getServiceName())
          .operationName(Pattern.compile(Pattern.quote("aerospike.query")))
          .resourceName(Pattern.compile(Pattern.quote(method)))
          .type(DDSpanTypes.AEROSPIKE);
    }
  }
}

@WithConfig(key = "trace.span.attribute.schema", value = "v0")
class AerospikeStreamingV0ForkedTest extends AerospikeStreamingTest {
  @Override
  int version() {
    return 0;
  }
}

@WithConfig(key = "trace.span.attribute.schema", value = "v1")
class AerospikeStreamingV1ForkedTest extends AerospikeStreamingTest {
  @Override
  int version() {
    return 1;
  }
}
