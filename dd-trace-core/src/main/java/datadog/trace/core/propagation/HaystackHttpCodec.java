package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;
import static datadog.trace.core.propagation.HttpCodec.validateUInt64BitsID;

import datadog.trace.api.DDId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
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

  // https://github.com/ExpediaDotCom/haystack-client-java/blob/master/core/src/main/java/com/expedia/www/haystack/client/propagation/DefaultKeyConvention.java
  private static final String OT_BAGGAGE_PREFIX = "Baggage-";
  private static final String TRACE_ID_KEY = "Trace-ID";
  private static final String SPAN_ID_KEY = "Span-ID";
  private static final String PARENT_ID_KEY = "Parent-ID";

  private static final String DD_TRACE_ID_BAGGAGE_KEY = OT_BAGGAGE_PREFIX + "Datadog-Trace-Id";
  private static final String DD_SPAN_ID_BAGGAGE_KEY = OT_BAGGAGE_PREFIX + "Datadog-Span-Id";
  private static final String DD_PARENT_ID_BAGGAGE_KEY = OT_BAGGAGE_PREFIX + "Datadog-Parent-Id";

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
        //
        // If the original trace has originated within Haystack system and we have it saved in Baggage, and it is equal
        //  to the converted value in BigInteger, use that instead.
        //  this will preserve the complete UUID/GUID without losing the most significant bit part
        String originalHaystackTraceId = getBaggageItemIgnoreCase(context.getBaggageItems(), HAYSTACK_TRACE_ID_BAGGAGE_KEY);
        String injectedTraceId = originalHaystackTraceId;
        if (originalHaystackTraceId != null && convertUUIDToBigInt(originalHaystackTraceId).equals(context.getTraceId())) {
          setter.set(carrier, TRACE_ID_KEY, originalHaystackTraceId);
        } else {
          injectedTraceId = convertBigIntToUUID(context.getTraceId());
          setter.set(carrier, TRACE_ID_KEY, injectedTraceId);
        }
        setter.set(carrier, DD_TRACE_ID_BAGGAGE_KEY, HttpCodec.encode(context.getTraceId().toString()));
        setter.set(carrier, SPAN_ID_KEY, convertBigIntToUUID(context.getSpanId()));
        setter.set(carrier, DD_SPAN_ID_BAGGAGE_KEY, HttpCodec.encode(context.getSpanId().toString()));
        setter.set(carrier, PARENT_ID_KEY, convertBigIntToUUID(context.getParentId()));
        setter.set(carrier, DD_PARENT_ID_BAGGAGE_KEY, HttpCodec.encode(context.getParentId().toString()));

        for (final Map.Entry<String, String> entry : context.baggageItems()) {
          setter.set(carrier, OT_BAGGAGE_PREFIX + entry.getKey(), HttpCodec.encode(entry.getValue()));
        }
        log.debug("{} - Haystack parent context injected - {}", context.getTraceId(), injectedTraceId);
      } catch (final NumberFormatException e) {
        log.debug(
          "Cannot parse context id(s): {} {}", context.getTraceId(), context.getSpanId(), e);
      }
    }

    private String getBaggageItemIgnoreCase(Map<String, String> baggage, String key) {
      for (final Map.Entry<String, String> mapping : baggage.entrySet()) {
        if (key.equalsIgnoreCase(mapping.getKey())) {
          return mapping.getValue();
        }
      }
      return null;
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

          // We are preserving the original UUID values as baggage to be able to loop them through the 2 systems
          if (baggage.isEmpty()) {
            baggage = new HashMap<>();
          }
          if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
            traceId = convertUUIDToBigInt(value);
            baggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, HttpCodec.decode(value));
          } else if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
            spanId = convertUUIDToBigInt(value);
            baggage.put(HAYSTACK_SPAN_ID_BAGGAGE_KEY, HttpCodec.decode(value));
          } else if (PARENT_ID_KEY.equalsIgnoreCase(key)) {
            baggage.put(HAYSTACK_PARENT_ID_BAGGAGE_KEY, HttpCodec.decode(value));
          } else if (key.startsWith(OT_BAGGAGE_PREFIX.toLowerCase())) {
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
  }

  private static String convertBigIntToUUID(BigInteger id) {
    // This is not a true/real UUID, as we don't care about the version and variant markers
    //  the creation is just taking the least significant bits and doing static most significant ones.
    //  this is done for the purpose of being able to maintain cardinality and idempotency of the conversion
    String idHex = String.format("%016x", id);
    return DATADOG + "-" + idHex.substring(0, 4) + "-" + idHex.substring(4);
  }

  private static BigInteger convertUUIDToBigInt(String value) {
    try {
      if (value.contains("-")) {
        String[] strings = value.split("-");
        // We are only interested in the least significant bit component, dropping the most
        // significant one.
        if (strings.length == 5) {
          String idHex = strings[3] + strings[4];
          return validateUInt64BitsID(idHex, 16);
        }
        throw new NumberFormatException("Invalid UUID format: " + value);
      } else {
        // This could be a regular hex id without separators
        int length = value.length();
        if (length == 32) {
          return validateUInt64BitsID(value.substring(16), 16);
        } else {
          return validateUInt64BitsID(value, 16);
        }
      }
    } catch (final Exception e) {
      throw new IllegalArgumentException("Exception when converting UUID to BigInteger: " + value, e);
    }
  }
}
