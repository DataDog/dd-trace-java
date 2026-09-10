package datadog.smoketest;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static datadog.smoketest.trace.SmokeTraceAssertions.UNORDERED;
import static datadog.smoketest.trace.SpanMatcher.span;
import static datadog.smoketest.trace.TraceMatcher.trace;
import static datadog.trace.test.junit.utils.assertions.Matchers.validates;
import static java.util.Comparator.comparing;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.trace.SpanMatcher;
import datadog.smoketest.trace.TraceMatcher.Options;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.io.File;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class OpenTelemetrySmokeTest {
  private static final UnaryOperator<Options> SORT_BY_RESOURCE_NAME =
      options -> options.sort(comparing(DecodedSpan::getResource));
  private static final int TIMEOUT_SECONDS = 30;
  private static final int SHARD_COUNT = 3;
  private static final String SPAN_LINKS_TAG = "_dd.span_links";

  /**
   * The span id of a serialized span link, e.g. {@code "span_id":"5d64d8cd8b229416"}. Matching the
   * id alone keeps this independent of how the rest of the link is laid out.
   */
  private static final Pattern SPAN_LINK_ID = Pattern.compile("\"span_id\":\"[0-9a-f]+\"");

  private static final File WORKING_DIRECTORY =
      new File(System.getProperty("datadog.smoketest.builddir"));
  private static final String APPLICATION_JAR =
      System.getProperty("datadog.smoketest.shadowJar.path");

  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("opentelemetry")
          .jar(APPLICATION_JAR)
          .jvmArgs("-Ddd.trace.otel.enabled=true")
          .workingDirectory(WORKING_DIRECTORY)
          .backend(testAgent())
          .build();

  @Test
  void receivesTraces() {
    app.traces()
        .waitForTraces(
            TIMEOUT_SECONDS,
            UNORDERED,
            // The standalone annotated span from the app set-up.
            trace(
                span()
                    .root()
                    .operationName("Application.loadConfiguration")
                    .tag("events", validates(events -> events.contains("configuration-loaded")))),
            // The batch trace:
            //   batch-job                        (tracer, server, root)
            //   ├─ Application.splitBatch        (annotated, main thread)
            //   ├─ shard-0                       (tracer, worker thread)   ┐
            //   ├─ shard-1                       (tracer, worker thread)   ├ fan-out
            //   ├─ shard-2                       (tracer, worker thread)   ┘
            //   └─ batch-merge                   (tracer, links to the shards) ← fan-in
            //      └─ Application.reportResults  (annotated, main thread)
            trace(
                SORT_BY_RESOURCE_NAME,
                // 0: Application.reportResults, child of batch-merge (index 3)
                span()
                    .operationName("Application.reportResults")
                    .childOfIndex(3)
                    .metric("batch.merged-shards", validates(shards -> shards.intValue() == 3)),
                // 1: Application.splitBatch, child of batch-job (index 2)
                span()
                    .operationName("Application.splitBatch")
                    .childOfIndex(2)
                    .metric("batch.shards", validates(shards -> shards.intValue() == 3)),
                // 2: batch-job, the root
                span().root().operationName("server.request").resourceName("batch-job"),
                // 3: batch-merge, fanning the shards (indexes 4 to 6) back in
                span()
                    .operationName("internal")
                    .resourceName("batch-merge")
                    .childOfIndex(2)
                    .tag(SPAN_LINKS_TAG, validates(OpenTelemetrySmokeTest::linksOnePerShard)),
                // 4 to 6: the shards, fanned out over the pool but all children of batch-job
                shard(0),
                shard(1),
                shard(2)));
    app.assertCompletesWithValue(TIMEOUT_SECONDS, SECONDS, 0);
  }

  /**
   * Checks a span-links tag holds exactly one well-formed link per shard merged.
   *
   * @param links The serialized {@value #SPAN_LINKS_TAG} tag value.
   * @return {@code true} if the tag holds one link per shard, {@code false} otherwise.
   */
  private static boolean linksOnePerShard(String links) {
    if (links == null) {
      return false;
    }
    Matcher link = SPAN_LINK_ID.matcher(links);
    int linkCount = 0;
    while (link.find()) {
      linkCount++;
    }
    return linkCount == SHARD_COUNT;
  }

  private static SpanMatcher shard(int shard) {
    return span()
        .operationName("internal")
        .resourceName("shard-" + shard)
        .childOfIndex(2)
        .metric("batch.shard", validates(value -> value.intValue() == shard));
  }
}
