package datadog.trace.instrumentation.bucket4j;

import static java.util.Collections.singleton;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import io.github.bucket4j.Bucket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Bucket4jDecorator extends BaseDecorator {
  public static final Bucket4jDecorator DECORATE = new Bucket4jDecorator();

  public static final CharSequence TRY_CONSUME = UTF8BytesString.create("bucket4j.try_consume");

  private static final Logger LOGGER = LoggerFactory.getLogger(Bucket4jDecorator.class);
  private static final CharSequence BUCKET4J = UTF8BytesString.create("bucket4j");

  private static final long[] TIER_THRESHOLDS = {1L, 10L, 100L, 1_000L, 10_000L};

  // pre-compute keys for the well-known default bandwidths once at load time
  public static final Map<String, Integer> KNOWN_LIMIT_KEYS = buildKnownLimitKeys();

  private static Map<String, Integer> buildKnownLimitKeys() {
    Map<String, Integer> keys = new HashMap<>();
    keys.put("default", Objects.hash("default", 100L));
    keys.put("burst", Objects.hash("burst", 1000L));
    return keys;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"bucket4j"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return BUCKET4J;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    super.afterStart(span);
    span.setSpanName(BUCKET4J);
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
    return span;
  }

  public void onConsume(AgentSpan span, Bucket bucket, long tokens, boolean consumed) {
    if (InstrumenterConfig.get().isIntegrationEnabled(singleton("bucket4j-tier"), true)) {
      long tier =
          Arrays.stream(TIER_THRESHOLDS)
              .filter(threshold -> tokens <= threshold)
              .findFirst()
              .orElse(-1L);
      span.setTag("bucket4j.tier", tier);
    }

    int metricKey = Objects.hash(bucket, tokens, consumed);
    span.setTag("bucket4j.metric_key", metricKey);
    span.setTag("bucket4j.tokens", tokens);
    span.setTag("bucket4j.consumed", consumed);

    LOGGER.debug(
        "bucket4j tryConsume tokens=" + tokens + " consumed=" + consumed + " bucket=" + bucket);
  }
}
