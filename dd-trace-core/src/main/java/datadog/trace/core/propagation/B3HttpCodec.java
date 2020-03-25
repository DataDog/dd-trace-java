package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;
import static datadog.trace.core.propagation.HttpCodec.validateUInt64BitsID;

import datadog.trace.core.DDSpanContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * A codec designed for HTTP transport via headers using B3 headers
 *
 * <p>TODO: there is fair amount of code duplication between DatadogHttpCodec and this class,
 * especially in part where TagContext is handled. We may want to refactor that and avoid special
 * handling of TagContext in other places (i.e. CompoundExtractor).
 */
@Slf4j
class B3HttpCodec {

  private static final String TRACE_ID_KEY = "X-B3-TraceId";
  private static final String SPAN_ID_KEY = "X-B3-SpanId";
  private static final String SAMPLING_PRIORITY_KEY = "X-B3-Sampled";
  private static final String SAMPLING_PRIORITY_ACCEPT = String.valueOf(1);
  private static final String SAMPLING_PRIORITY_DROP = String.valueOf(0);
  private static final int HEX_RADIX = 16;

  private B3HttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      try {
        setter.set(carrier, TRACE_ID_KEY, context.getTraceId().toString(HEX_RADIX).toLowerCase());
        setter.set(carrier, SPAN_ID_KEY, context.getSpanId().toString(HEX_RADIX).toLowerCase());

        if (context.lockSamplingPriority()) {
          setter.set(
              carrier,
              SAMPLING_PRIORITY_KEY,
              convertSamplingPriority(context.getSamplingPriority()));
        }
        log.debug("{} - B3 parent context injected", context.getTraceId());
      } catch (final NumberFormatException e) {
        log.debug(
            "Cannot parse context id(s): {} {}", context.getTraceId(), context.getSpanId(), e);
      }
    }

    private String convertSamplingPriority(final int samplingPriority) {
      return samplingPriority > 0 ? SAMPLING_PRIORITY_ACCEPT : SAMPLING_PRIORITY_DROP;
    }
  }

  public static class Extractor implements HttpCodec.Extractor {

    private final Map<String, String> taggedHeaders;

    public Extractor(final Map<String, String> taggedHeaders) {
      this.taggedHeaders = new HashMap<>();
      for (final Map.Entry<String, String> mapping : taggedHeaders.entrySet()) {
        this.taggedHeaders.put(mapping.getKey().trim().toLowerCase(), mapping.getValue());
      }
    }

    @Override
    public <C> TagContext extract(final C carrier, final AgentPropagation.Getter<C> getter) {
      try {
        Map<String, String> tags = Collections.emptyMap();
        BigInteger traceId = BigInteger.ZERO;
        BigInteger spanId = BigInteger.ZERO;
        int samplingPriority = PrioritySampling.UNSET;

        for (final String uncasedKey : getter.keys(carrier)) {
          final String key = uncasedKey.toLowerCase();
          final String value = firstHeaderValue(getter.get(carrier, uncasedKey));

          if (value == null) {
            continue;
          }

          if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
            final String trimmedValue;
            final int length = value.length();
            if (length > 32) {
              log.debug("Header {} exceeded max length of 32: {}", TRACE_ID_KEY, value);
              traceId = BigInteger.ZERO;
              continue;
            } else if (length > 16) {
              trimmedValue = value.substring(length - 16);
            } else {
              trimmedValue = value;
            }
            traceId = validateUInt64BitsID(trimmedValue, HEX_RADIX);
          } else if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
            spanId = validateUInt64BitsID(value, HEX_RADIX);
          } else if (SAMPLING_PRIORITY_KEY.equalsIgnoreCase(key)) {
            samplingPriority = convertSamplingPriority(value);
          }

          if (taggedHeaders.containsKey(key)) {
            if (tags.isEmpty()) {
              tags = new HashMap<>();
            }
            tags.put(taggedHeaders.get(key), HttpCodec.decode(value));
          }
        }

        if (!BigInteger.ZERO.equals(traceId)) {
          final ExtractedContext context =
              new ExtractedContext(
                  traceId,
                  spanId,
                  samplingPriority,
                  null,
                  Collections.<String, String>emptyMap(),
                  tags);
          context.lockSamplingPriority();

          log.debug("{} - Parent context extracted", context.getTraceId());
          return context;
        } else if (!tags.isEmpty()) {
          log.debug("Tags context extracted");
          return new TagContext(null, tags);
        }
      } catch (final RuntimeException e) {
        log.debug("Exception when extracting context", e);
      }

      return null;
    }

    private int convertSamplingPriority(final String samplingPriority) {
      return Integer.parseInt(samplingPriority) == 1
          ? PrioritySampling.SAMPLER_KEEP
          : PrioritySampling.SAMPLER_DROP;
    }
  }
}
