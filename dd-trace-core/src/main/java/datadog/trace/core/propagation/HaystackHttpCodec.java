package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;

import datadog.trace.api.DDId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
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

  private static final String DD_TRACE_ID_BAGGAGE_KEY = OT_BAGGAGE_PREFIX + "x-datadog-trace-id";
  private static final String DD_SPAN_ID_BAGGAGE_KEY = OT_BAGGAGE_PREFIX + "x-datadog-span-id";
  private static final String DD_PARENT_ID_BAGGAGE_KEY = OT_BAGGAGE_PREFIX + "x-datadog-parent-id";

  private static final String HAYSTACK_TRACE_ID_BAGGAGE_KEY = "Haystack-Trace-ID";
  private static final String HAYSTACK_SPAN_ID_BAGGAGE_KEY = "Haystack-Span-ID";
  private static final String HAYSTACK_PARENT_ID_BAGGAGE_KEY = "Haystack-Parent-ID";

  //public static final long DATADOG = new BigInteger("Datadog!".getBytes()).longValue();
  public static final String DATADOG = "44617461-646f-6721";

  private HaystackHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      try {
        // Given that Haystack uses a 128-bit UUID/GUID for all ID representations, need to convert from 64-bit BigInteger
        //  also record the original DataDog IDs into Baggage payload
        setter.put(TRACE_ID_KEY, convertBigIntToUUID(context.getTraceId()));
        setter.put(DD_TRACE_ID_BAGGAGE_KEY, HttpCodec.encode(context.getTraceId().toString()));
        setter.put(SPAN_ID_KEY, convertBigIntToUUID(context.getSpanId()));
        setter.put(DD_SPAN_ID_BAGGAGE_KEY, HttpCodec.encode(context.getSpanId().toString()));
        setter.put(PARENT_ID_KEY, convertBigIntToUUID(context.getParentId()));
        setter.put(DD_PARENT_ID_BAGGAGE_KEY, HttpCodec.encode(context.getParentId().toString()));

        for (final Map.Entry<String, String> entry : context.baggageItems()) {
          setter.put(OT_BAGGAGE_PREFIX + entry.getKey(), HttpCodec.encode(entry.getValue()));
        }
        log.debug("{} - Haystack parent context injected", context.getTraceId());
      } catch (final NumberFormatException e) {
        log.debug(
          "Cannot parse context id(s): {} {}", context.getTraceId(), context.getSpanId(), e);
      }
    }

    private String convertBigIntToUUID(BigInteger id) {
      // This is not a true/real UUID, as we don't care about the version and variant markers
      //  the creation is just taking the least significant bits and doing static most significant ones.
      //  this is done for the purpose of being able to maintain cardinality and idempotency of the conversion
      String idHex = String.format("%016x", id);
      return DATADOG + "-" + idHex.substring(0, 4) + "-" + idHex.substring(4);

//      UUID uuid = new UUID(DATADOG, id.longValue());
//      return uuid.toString();
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
        DDId traceId = DDId.ZERO;
        DDId spanId = DDId.ZERO;
        final int samplingPriority = PrioritySampling.SAMPLER_KEEP;
        final String origin = null; // Always null

        for (final String uncasedKey : getter.keys(carrier)) {
          final String key = uncasedKey.toLowerCase();
          final String value = firstHeaderValue(getter.get(carrier, uncasedKey));

          if (value == null) {
            continue;
          }

          // We are preserving the original UUID values as baggage to be able to loop them through the 2 systems
          if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
            traceId = convertUUIDToBigInt(value);
            baggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, HttpCodec.decode(value));
          } else if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
            spanId = convertUUIDToBigInt(value);
            baggage.put(HAYSTACK_SPAN_ID_BAGGAGE_KEY, HttpCodec.decode(value));
          } else if (PARENT_ID_KEY.equalsIgnoreCase(key)) {
            baggage.put(HAYSTACK_PARENT_ID_BAGGAGE_KEY, HttpCodec.decode(value));
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

        if (!DDId.ZERO.equals(traceId)) {
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

    private DDId convertUUIDToBigInt(String value) {
      try {
        if (value.contains("-")) {
          String[] strings = value.split("-");
          // We are only interested in the least significant bit component, dropping the most
          // significant one.
          if (strings.length == 5) {
            String idHex = strings[3] + strings[4];
            return DDId.from(idHex);
          }
          throw new NumberFormatException("Invalid UUID format: " + value);
        } else {
          // This could be a regular hex id without separators
          int length = value.length();
          if (length == 32) {
            return DDId.from(value.substring(16));
          } else {
            return DDId.from(value);
          }
        }
      } catch (final Exception e) {
        throw new IllegalArgumentException("Exception when converting UUID to BigInteger: " + value, e);
      }
    }
  }
}
