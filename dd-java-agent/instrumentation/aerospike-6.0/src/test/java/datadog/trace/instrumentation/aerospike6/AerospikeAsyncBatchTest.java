package datadog.trace.instrumentation.aerospike6;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNonNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.testcontainers.containers.wait.strategy.Wait.forLogMessage;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.BatchRead;
import com.aerospike.client.BatchRecord;
import com.aerospike.client.Bin;
import com.aerospike.client.Key;
import com.aerospike.client.Operation;
import com.aerospike.client.ResultCode;
import com.aerospike.client.async.EventLoops;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.listener.BatchOperateListListener;
import com.aerospike.client.listener.BatchRecordArrayListener;
import com.aerospike.client.listener.BatchRecordSequenceListener;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.github.dockerjava.api.model.Ulimit;
import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

abstract class AerospikeAsyncBatchTest extends AbstractInstrumentationTest {
  private static GenericContainer<?> aerospike;
  private AerospikeClient client;
  private EventLoops eventLoops;

  @BeforeAll
  static void startServer() {
    aerospike =
        new GenericContainer<>(DockerImageName.parse(System.getProperty("test.aerospike.image")))
            .withExposedPorts(3000)
            .withCreateContainerCmdModifier(cmd ->
                cmd.getHostConfig()
                    .withUlimits(new Ulimit[] {new Ulimit("nofile", 15000L, 15000L)}))
            .waitingFor(forLogMessage(".*heartbeat-received.*\\n", 1));
    aerospike.start();
  }

  @AfterAll
  static void stopServer() {
    if (aerospike != null) aerospike.stop();
  }

  @BeforeEach
  void createClient() {
    eventLoops = new NioEventLoops(1);
    ClientPolicy policy = new ClientPolicy();
    policy.eventLoops = eventLoops;
    client = new AerospikeClient(policy, "localhost", aerospike.getMappedPort(3000));
  }

  @AfterEach
  void closeClient() {
    try {
      if (client != null) client.close();
    } finally {
      if (eventLoops != null) eventLoops.close();
    }
  }

  abstract String service();

  @ParameterizedTest
  @ValueSource(strings = {"array", "list", "sequence"})
  void mixedResultsPreserveCallbackParentage(String shape) throws Throwable {
    Result result = new Result();
    client.put((WritePolicy) null, result.keys[0], new Bin("name", "value"));
    // Queue outside the caller trace so callbacks cannot borrow its active scope.
    eventLoops
        .next()
        .execute(() -> result.check(() -> assertTrue(result.callerClosed.await(10, SECONDS))));
    try {
      result.call("batch-caller", () -> submit(shape, result));
    } finally {
      result.callerClosed.countDown();
    }
    result.await();
    // Join after the terminal wrapper closes its resumed continuation scope.
    CountDownLatch drained = new CountDownLatch(1);
    eventLoops.next().execute(drained::countDown);
    assertTrue(drained.await(10, SECONDS));
    assertNull(activeSpan());
    assertEquals(1, result.terminals.get());
    assertEquals(shape.equals("sequence") ? 2 : 0, result.received.size());
    List<SpanMatcher> spans = new ArrayList<>();
    spans.add(span().root().operationName("batch-caller"));
    spans.add(
        aerospikeSpan("operate")
            .childOfIndex(0)
            .id(result.operationId.get())
            .topLevel(!service().equals(Config.get().getServiceName())));
    for (int index : result.received) {
      spans.add(span().childOfIndex(1).operationName("batch-record-" + index));
    }
    spans.add(span().childOfIndex(1).operationName("batch-terminal"));
    assertTraces(
        trace(aerospikeSpan("put").root().topLevel()),
        trace(SORT_BY_START_TIME, spans.toArray(new SpanMatcher[0])));
  }

  private void submit(String shape, Result result) {
    if (shape.equals("array")) {
      client.operate(
          eventLoops.next(),
          (BatchRecordArrayListener) result,
          null,
          null,
          result.keys,
          Operation.get("name"));
    } else if (shape.equals("list")) {
      client.operate(eventLoops.next(), (BatchOperateListListener) result, null, result.records);
    } else {
      client.operate(eventLoops.next(), (BatchRecordSequenceListener) result, null, result.records);
    }
  }

  private SpanMatcher aerospikeSpan(String method) {
    boolean v1 = Config.get().getSpanAttributeSchemaVersion() == 1;
    return span()
        .serviceName(service())
        .operationName(Pattern.compile(Pattern.quote("aerospike.query")))
        .resourceName(Pattern.compile(Pattern.quote("AerospikeClient." + method)))
        .type(DDSpanTypes.AEROSPIKE)
        .error(false)
        .tags(
            defaultTags(),
            tag(Tags.COMPONENT, matches(Pattern.compile(Pattern.quote("java-aerospike")))),
            tag(Tags.SPAN_KIND, matches(Pattern.compile(Pattern.quote(Tags.SPAN_KIND_CLIENT)))),
            tag(Tags.PEER_HOSTNAME, matches(Pattern.compile(Pattern.quote("localhost")))),
            tag(Tags.PEER_HOST_IPV4, matches(Pattern.compile(Pattern.quote("127.0.0.1")))),
            tag(Tags.PEER_PORT, is(aerospike.getMappedPort(3000))),
            tag(Tags.DB_TYPE, matches(Pattern.compile(Pattern.quote("aerospike")))),
            tag(Tags.DB_INSTANCE, matches(Pattern.compile(Pattern.quote("test")))),
            tag(DDTags.DD_SVC_SRC, v1 ? isNull() : isNonNull()),
            tag(Tags.PEER_SERVICE, v1 ? matches(Pattern.compile(Pattern.quote("test"))) : isNull()),
            tag(
                DDTags.PEER_SERVICE_SOURCE,
                v1 ? matches(Pattern.compile(Pattern.quote(Tags.DB_INSTANCE))) : isNull()));
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Throwable;
  }

  private static class Result
      implements BatchRecordArrayListener, BatchOperateListListener, BatchRecordSequenceListener {
    private static final ContextKey<String> MARKER = ContextKey.named("aerospike-batch-runtime");
    final Key[] keys;
    final List<BatchRecord> records = new ArrayList<>();
    final List<Integer> received = new ArrayList<>();
    final AtomicLong operationId = new AtomicLong();
    final AtomicInteger terminals = new AtomicInteger();
    final CountDownLatch callerClosed = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    Result() {
      String set = "batch_" + UUID.randomUUID().toString().replace("-", "");
      keys = new Key[] {new Key("test", set, "present"), new Key("test", set, "missing")};
      for (Key key : keys) records.add(new BatchRead(key, new String[] {"name"}));
    }

    @Override
    public void onSuccess(BatchRecord[] returned, boolean status) {
      complete(() -> successfulRecords(Arrays.asList(returned), status));
    }

    @Override
    public void onSuccess(List<BatchRecord> returned, boolean status) {
      complete(() -> {
        assertSame(records, returned);
        successfulRecords(returned, status);
      });
    }

    private void successfulRecords(List<BatchRecord> returned, boolean status) {
      assertFalse(status);
      assertResults(returned);
      callback("batch-terminal");
    }

    @Override
    public void onRecord(BatchRecord record, int index) {
      check(() -> {
        assertTrue(index >= 0 && index < records.size());
        assertFalse(received.contains(index));
        received.add(index);
        assertSame(records.get(index), record);
        assertRecord(record, index);
        callback("batch-record-" + index);
      });
    }

    @Override
    public void onSuccess() {
      complete(() -> {
        assertTrue(received.containsAll(Arrays.asList(0, 1)));
        assertResults(records);
        callback("batch-terminal");
      });
    }

    @Override
    public void onFailure(BatchRecord[] returned, AerospikeException error) {
      onFailure(error);
    }

    @Override
    public void onFailure(AerospikeException error) {
      complete(() -> {
        throw error;
      });
    }

    void call(String name, Runnable action) {
      AgentSpan span = startSpan("test", name);
      try (ContextScope ignored = activateSpan(span);
          ContextScope marker = Context.current().with(MARKER, "captured").attach()) {
        action.run();
        assertSame(span, activeSpan());
      } finally {
        span.finish();
      }
    }

    void callback(String name) {
      assertEquals(0, callerClosed.getCount());
      assertEquals("captured", Context.current().get(MARKER));
      assertNotNull(activeSpan());
      assertEquals("AerospikeClient.operate", activeSpan().getResourceName().toString());
      operationId.compareAndSet(0, activeSpan().getSpanId());
      assertEquals(operationId.get(), activeSpan().getSpanId());
      call(name, () -> {});
    }

    void assertResults(List<BatchRecord> returned) {
      assertEquals(keys.length, returned.size());
      for (int i = 0; i < returned.size(); i++) assertRecord(returned.get(i), i);
    }

    void assertRecord(BatchRecord record, int index) {
      assertSame(keys[index], record.key);
      assertFalse(record.inDoubt);
      assertEquals(index == 0 ? ResultCode.OK : ResultCode.KEY_NOT_FOUND_ERROR, record.resultCode);
      if (index == 0) assertEquals(Collections.singletonMap("name", "value"), record.record.bins);
      else assertNull(record.record);
    }

    void check(CheckedAction action) {
      try {
        action.run();
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
      }
    }

    void complete(CheckedAction action) {
      terminals.incrementAndGet();
      check(action);
      done.countDown();
    }

    void await() throws Throwable {
      boolean completed = done.await(10, SECONDS);
      if (failure.get() != null) throw failure.get();
      assertTrue(completed, "Aerospike batch callback timed out");
    }
  }
}

@WithConfig(key = "trace.span.attribute.schema", value = "v0")
class AerospikeAsyncBatchV0ForkedTest extends AerospikeAsyncBatchTest {
  @Override
  String service() {
    return "aerospike";
  }
}

@WithConfig(key = "trace.span.attribute.schema", value = "v1")
class AerospikeAsyncBatchV1ForkedTest extends AerospikeAsyncBatchTest {
  @Override
  String service() {
    return Config.get().getServiceName();
  }
}
