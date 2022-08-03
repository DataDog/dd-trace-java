package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;

import datadog.trace.api.DDId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A codec designed for HTTP transport via headers using Haystack headers.
 *
 * @author Alex Antonov
 */
class HaystackHttpCodec {

  private static final Logger log = LoggerFactory.getLogger(HaystackHttpCodec.class);

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

  // public static final long DATADOG = new BigInteger("Datadog!".getBytes()).longValue();
  public static final String DATADOG = "44617461-646f-6721";

  private HaystackHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static final HttpCodec.Injector INJECTOR = new Injector();

  private static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      try {
        // Given that Haystack uses a 128-bit UUID/GUID for all ID representations, need to convert
        // from 64-bit BigInteger
        //  also record the original DataDog IDs into Baggage payload
        //
        // If the original trace has originated within Haystack system and we have it saved in
        // Baggage, and it is equal
        //  to the converted value in BigInteger, use that instead.
        //  this will preserve the complete UUID/GUID without losing the most significant bit part
        String originalHaystackTraceId =
            getBaggageItemIgnoreCase(context.getBaggageItems(), HAYSTACK_TRACE_ID_BAGGAGE_KEY);
        String injectedTraceId;
        if (originalHaystackTraceId != null
            && convertUUIDToBigInt(originalHaystackTraceId).equals(context.getTraceId())) {
          injectedTraceId = originalHaystackTraceId;
        } else {
          injectedTraceId = convertBigIntToUUID(context.getTraceId());
        }
        setter.set(carrier, TRACE_ID_KEY, injectedTraceId);
        context.setTag(HAYSTACK_TRACE_ID_BAGGAGE_KEY, injectedTraceId);
        setter.set(
            carrier, DD_TRACE_ID_BAGGAGE_KEY, HttpCodec.encode(context.getTraceId().toString()));
        setter.set(carrier, SPAN_ID_KEY, convertBigIntToUUID(context.getSpanId()));
        setter.set(
            carrier, DD_SPAN_ID_BAGGAGE_KEY, HttpCodec.encode(context.getSpanId().toString()));
        setter.set(carrier, PARENT_ID_KEY, convertBigIntToUUID(context.getParentId()));
        setter.set(
            carrier, DD_PARENT_ID_BAGGAGE_KEY, HttpCodec.encode(context.getParentId().toString()));

        for (final Map.Entry<String, String> entry : context.baggageItems()) {
          setter.set(
              carrier, OT_BAGGAGE_PREFIX + entry.getKey(), HttpCodec.encode(entry.getValue()));
        }
        log.debug(
            "{} - Haystack parent context injected - {}", context.getTraceId(), injectedTraceId);
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

  public static HttpCodec.Extractor newExtractor(final Map<String, String> tagMapping) {
    return new TagContextExtractor(
        tagMapping,
        new ContextInterpreter.Factory() {
          @Override
          protected ContextInterpreter construct(Map<String, String> mapping) {
            return new HaystackContextInterpreter(mapping);
          }
        });
  }

  private static class HaystackContextInterpreter extends ContextInterpreter {

    private static final String BAGGAGE_PREFIX_LC = "baggage-";

    private static final int TRACE_ID = 0;
    private static final int SPAN_ID = 1;
    private static final int PARENT_ID = 2;
    private static final int TAGS = 3;
    private static final int BAGGAGE = 4;
    private static final int IGNORE = -1;

    private HaystackContextInterpreter(Map<String, String> taggedHeaders) {
      super(taggedHeaders);
    }

    @Override
    public boolean accept(String key, String value) {
      if (null == key || key.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }
      char first = Character.toLowerCase(key.charAt(0));
      String lowerCaseKey = null;
      int classification = IGNORE;
      switch (first) {
        case 't':
          if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
            classification = TRACE_ID;
          }
          break;
        case 's':
          if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
            classification = SPAN_ID;
          }
          break;
        case 'p':
          if (PARENT_ID_KEY.equalsIgnoreCase(key)) {
            classification = PARENT_ID;
          }
          break;
        case 'x':
          if (handledXForwarding(key, value)) {
            return true;
          }
          break;
        case 'f':
          if (handledForwarding(key, value)) {
            return true;
          }
          break;
        case 'b':
          lowerCaseKey = toLowerCase(key);
          if (lowerCaseKey.startsWith(BAGGAGE_PREFIX_LC)) {
            classification = BAGGAGE;
          }
          break;
        case 'u':
          if (handledUserAgent(key, value)) {
            return true;
          }
          break;
        default:
      }

      if (handledIpHeaders(key, value)) {
        return true;
      }

      if (!taggedHeaders.isEmpty() && classification == IGNORE) {
        lowerCaseKey = toLowerCase(key);
        if (taggedHeaders.containsKey(lowerCaseKey)) {
          classification = TAGS;
        }
      }
      if (IGNORE != classification) {
        try {
          String firstValue = firstHeaderValue(value);
          if (null != firstValue) {
            switch (classification) {
              case TRACE_ID:
                traceId = convertUUIDToBigInt(value);
                addBaggageItem(HAYSTACK_TRACE_ID_BAGGAGE_KEY, value);
                break;
              case SPAN_ID:
                spanId = convertUUIDToBigInt(value);
                addBaggageItem(HAYSTACK_SPAN_ID_BAGGAGE_KEY, value);
                break;
              case PARENT_ID:
                addBaggageItem(HAYSTACK_PARENT_ID_BAGGAGE_KEY, value);
                break;
              case TAGS:
                {
                  String mappedKey = taggedHeaders.get(lowerCaseKey);
                  if (null != mappedKey) {
                    if (tags.isEmpty()) {
                      tags = new TreeMap<>();
                    }
                    tags.put(mappedKey, HttpCodec.decode(value));
                  }
                  break;
                }
              case BAGGAGE:
                {
                  addBaggageItem(lowerCaseKey.substring(BAGGAGE_PREFIX_LC.length()), value);
                  break;
                }
              default:
            }
          }
        } catch (RuntimeException e) {
          invalidateContext();
          log.debug("Exception when extracting context", e);
          return false;
        }
      }
      return true;
    }

    private void addBaggageItem(String key, String value) {
      if (baggage.isEmpty()) {
        baggage = new TreeMap<>();
      }
      baggage.put(key, HttpCodec.decode(value));
    }

    @Override
    protected int defaultSamplingPriority() {
      return PrioritySampling.SAMPLER_KEEP;
    }

    @Override
    protected int defaultSamplingMechanism() {
      return SamplingMechanism.DEFAULT;
    }
  }

  private static String convertBigIntToUUID(DDId id) {
    // This is not a true/real UUID, as we don't care about the version and variant markers
    //  the creation is just taking the least significant bits and doing static most significant
    // ones.
    //  this is done for the purpose of being able to maintain cardinality and idempotence of the
    // conversion
    String idHex = String.format("%016x", id.toLong());
    return DATADOG + "-" + idHex.substring(0, 4) + "-" + idHex.substring(4);
  }

  @SuppressForbidden
  private static DDId convertUUIDToBigInt(String value) {
    try {
      if (value.contains("-")) {
        String[] strings = value.split("-");
        // We are only interested in the least significant bit component, dropping the most
        // significant one.
        if (strings.length == 5) {
          String idHex = strings[3] + strings[4];
          return DDId.fromHex(idHex);
        }
        throw new NumberFormatException("Invalid UUID format: " + value);
      } else {
        // This could be a regular hex id without separators
        int length = value.length();
        if (length == 32) {
          return DDId.fromHex(value.substring(16));
        } else {
          return DDId.fromHex(value);
        }
      }
    } catch (final Exception e) {
      throw new IllegalArgumentException(
          "Exception when converting UUID to BigInteger: " + value, e);
    }
  }
}
