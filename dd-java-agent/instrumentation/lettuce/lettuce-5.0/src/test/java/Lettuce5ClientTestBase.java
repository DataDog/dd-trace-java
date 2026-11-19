import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNonNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.redis.testcontainers.RedisContainer;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

@WithConfig(key = TRACE_SPAN_ATTRIBUTE_SCHEMA, value = "v0")
abstract class Lettuce5ClientTestBase extends AbstractInstrumentationTest {
  static final int DB_INDEX = 0;
  // Disable autoreconnect so we do not get stray traces popping up on server shutdown
  static final ClientOptions CLIENT_OPTIONS = ClientOptions.builder().autoReconnect(false).build();
  // Upper bound for asynchronous callbacks: generous for slow CI runners, free when they pass.
  static final long CALLBACK_TIMEOUT_SECONDS = 5;

  final Map<String, String> testHashMap = new HashMap<>();
  final RedisContainer redisServer =
      new RedisContainer(
              DockerImageName.parse(System.getProperty("test.redis.image"))
                  .asCompatibleSubstituteFor("redis"))
          .waitingFor(Wait.forListeningPort());

  int port;
  int incorrectPort;
  String dbAddr;
  String dbAddrNonExistent;
  String dbUriNonExistent;
  String embeddedDbUri;

  private RedisClient redisClient;
  StatefulRedisConnection<String, String> connection;
  RedisReactiveCommands<String, String> reactiveCommands;
  RedisAsyncCommands<String, String> asyncCommands;
  RedisCommands<String, String> syncCommands;

  @BeforeEach
  void setUpRedis() throws Exception {
    redisServer.start();

    port = redisServer.getFirstMappedPort();
    incorrectPort = PortUtils.randomOpenPort();
    dbAddr = redisServer.getHost() + ":" + port + "/" + DB_INDEX;
    dbAddrNonExistent = redisServer.getHost() + ":" + incorrectPort + "/" + DB_INDEX;
    dbUriNonExistent = "redis://" + dbAddrNonExistent;
    embeddedDbUri = "redis://" + dbAddr;

    testHashMap.put("firstname", "John");
    testHashMap.put("lastname", "Doe");
    testHashMap.put("age", "53");

    redisClient = createClient(embeddedDbUri);

    runUnderTrace(
        "setup",
        () -> {
          new PollingConditions(15)
              .delay(3)
              .eventually(() -> assertNotNull(connection = redisClient.connect()));
          reactiveCommands = connection.reactive();
          asyncCommands = connection.async();
          syncCommands = connection.sync();
          syncCommands.set("TESTKEY", "TESTVAL");
          syncCommands.hmset("TESTHM", testHashMap);
        });

    writer.waitForTraces(1);
    writer.clear();
  }

  @AfterEach
  void cleanUpRedis() throws Exception {
    try {
      if (connection != null) {
        connection.close();
      }
      if (redisClient != null) {
        shutdown(redisClient);
      }
    } finally {
      redisServer.stop();
    }
  }

  /**
   * Shuts a client down without a quiet period. The wait exceeds Netty's own shutdown timeout so
   * that a slow but successful shutdown is not reported as a timeout.
   */
  static void shutdown(RedisClient client) throws Exception {
    client.shutdownAsync(0, 10, SECONDS).get(15, SECONDS);
  }

  /** Creates a client for a single test, with the shared options. */
  static RedisClient createClient(String uri) {
    RedisClient client = RedisClient.create(uri);
    client.setOptions(CLIENT_OPTIONS);
    return client;
  }

  @FunctionalInterface
  interface TracedAction<E extends Throwable> {
    void run() throws E;
  }

  static <E extends Throwable> void runUnderTrace(String operationName, TracedAction<E> action)
      throws E {
    AgentSpan span = startSpan("test", operationName);
    try (ContextScope scope = activateSpan(span)) {
      action.run();
    } finally {
      span.finish();
    }
  }

  int namingVersion() {
    return 0;
  }

  SpanMatcher redisSpan(String resource, TagsMatcher... extraTags) {
    return redisSpan(resource, port, false, false, extraTags);
  }

  SpanMatcher redisChildSpan(String resource, TagsMatcher... extraTags) {
    return redisSpan(resource, port, false, true, extraTags).childOfIndex(0);
  }

  SpanMatcher redisSpan(
      String resource, int peerPort, boolean errored, boolean child, TagsMatcher... extraTags) {
    boolean v1 = namingVersion() == 1;
    String host = redisServer.getHost();
    List<TagsMatcher> tags = new ArrayList<>(Arrays.asList(defaultSpanTags(child)));
    tags.addAll(
        Arrays.asList(
            stringTag(Tags.COMPONENT, "redis-client"),
            stringTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT),
            stringTag(Tags.PEER_HOSTNAME, host),
            tag(Tags.PEER_PORT, is(peerPort)),
            stringTag(Tags.DB_TYPE, "redis"),
            tag("db.redis.dbIndex", is(DB_INDEX)),
            stringTag(DDTags.DD_INTEGRATION, "redis-client"),
            tag(DDTags.DD_SVC_SRC, v1 ? isNull() : isNonNull()),
            tag(Tags.PEER_SERVICE, v1 ? matches(Pattern.quote(host)) : isNull()),
            tag(
                DDTags.PEER_SERVICE_SOURCE,
                v1 ? matches(Pattern.quote(Tags.PEER_HOSTNAME)) : isNull())));
    tags.addAll(Arrays.asList(extraTags));
    if (errored) {
      tags.add(tag(DDTags.ERROR_STACK, validates(value -> value instanceof String)));
    }
    return span()
        .serviceName(v1 ? Config.get().getServiceName() : "redis")
        .operationName(Pattern.compile(Pattern.quote(v1 ? "redis.command" : "redis.query")))
        .resourceName(Pattern.compile(Pattern.quote(resource)))
        .type(DDSpanTypes.REDIS)
        .error(errored)
        .tags(tags.toArray(new TagsMatcher[0]));
  }

  SpanMatcher parentSpan(String resource) {
    return span()
        .operationName(Pattern.compile(Pattern.quote(resource)))
        .resourceName(Pattern.compile(Pattern.quote(resource)))
        .error(false)
        .tags(defaultSpanTags(false));
  }

  private TagsMatcher[] defaultSpanTags(boolean child) {
    if (child) {
      return new TagsMatcher[] {
        defaultTags(),
        tag(DDTags.RUNTIME_ID_TAG, isNull()),
        tag(DDTags.TRACER_HOST, isNull()),
        tag(DDTags.LANGUAGE_TAG_KEY, isNull())
      };
    }
    return new TagsMatcher[] {
      defaultTags(),
      tag(DDTags.SCHEMA_VERSION_TAG_KEY, is(namingVersion())),
      stringTag(DDTags.RUNTIME_ID_TAG, Config.get().getRuntimeId()),
      stringTag(DDTags.TRACER_HOST, Config.get().getHostName()),
      stringTag(DDTags.LANGUAGE_TAG_KEY, DDTags.LANGUAGE_TAG_VALUE)
    };
  }

  private static TagsMatcher stringTag(String tagName, String value) {
    return tag(tagName, matches(Pattern.quote(value)));
  }
}
