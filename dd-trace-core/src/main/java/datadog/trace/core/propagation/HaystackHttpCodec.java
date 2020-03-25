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
 * A codec designed for HTTP transport via headers using Haystack headers.
 *
 * @author Alex Antonov
 */
@Slf4j
public class HaystackHttpCodec {

  private static final String OT_BAGGAGE_PREFIX = "Baggage-";
  private static final String TRACE_ID_KEY = "Trace-ID";
  private static final String SPAN_ID_KEY = "Span-ID";
  private static final String PARENT_ID_KEY = "Parent_ID";

  private HaystackHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      setter.set(carrier, TRACE_ID_KEY, context.getTraceId().toString());
      setter.set(carrier, SPAN_ID_KEY, context.getSpanId().toString());
      setter.set(carrier, PARENT_ID_KEY, context.getParentId().toString());

      for (final Map.Entry<String, String> entry : context.baggageItems()) {
        setter.set(carrier, OT_BAGGAGE_PREFIX + entry.getKey(), HttpCodec.encode(entry.getValue()));
      }
      log.debug("{} - Haystack parent context injected", context.getTraceId());
    }
  }

  public static class Extractor implements HttpCodec.Extractor {
    private final Map<String, String> taggedHeaders;

    /** Creates Header Extractor using Haystack propagation. */
    public Extractor(final Map<String, String> taggedHeaders) {
      this.taggedHeaders = new HashMap<>();
      for (final Map.Entry<String, String> mapping : taggedHeaders.entrySet()) {
        this.taggedHeaders.put(mapping.getKey().trim().toLowerCase(), mapping.getValue());
      }
    }

    @Override
    public <C> TagContext extract(final C carrier, final AgentPropagation.Getter<C> getter) {
      try {
        Map<String, String> baggage = Collections.emptyMap();
        Map<String, String> tags = Collections.emptyMap();
        BigInteger traceId = BigInteger.ZERO;
        BigInteger spanId = BigInteger.ZERO;
        final int samplingPriority = PrioritySampling.SAMPLER_KEEP;
        final String origin = null; // Always null

        for (final String uncasedKey : getter.keys(carrier)) {
          final String key = uncasedKey.toLowerCase();
          final String value = firstHeaderValue(getter.get(carrier, uncasedKey));

          if (value == null) {
            continue;
          }

          if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
            traceId = validateUInt64BitsID(value, 10);
          } else if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
            spanId = validateUInt64BitsID(value, 10);
          } else if (key.startsWith(OT_BAGGAGE_PREFIX.toLowerCase())) {
            if (baggage.isEmpty()) {
              baggage = new HashMap<>();
            }
            baggage.put(key.replace(OT_BAGGAGE_PREFIX.toLowerCase(), ""), HttpCodec.decode(value));
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
              new ExtractedContext(traceId, spanId, samplingPriority, origin, baggage, tags);
          context.lockSamplingPriority();

          log.debug("{} - Parent context extracted", context.getTraceId());
          return context;
        } else if (origin != null || !tags.isEmpty()) {
          log.debug("Tags context extracted");
          return new TagContext(origin, tags);
        }
      } catch (final RuntimeException e) {
        log.debug("Exception when extracting context", e);
      }

      return null;
    }
  }
}
