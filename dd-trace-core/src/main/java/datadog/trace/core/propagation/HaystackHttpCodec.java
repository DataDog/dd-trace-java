package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;

import datadog.trace.api.DDId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import java.util.TreeMap;
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
    private static final int TAGS = 2;
    private static final int BAGGAGE = 3;
    private static final int IGNORE = -1;

    private HaystackContextInterpreter(Map<String, String> taggedHeaders) {
      super(taggedHeaders);
    }

    @Override
    public boolean accept(String key, String value) {
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
        case 'b':
          lowerCaseKey = toLowerCase(key);
          if (lowerCaseKey.startsWith(BAGGAGE_PREFIX_LC)) {
            classification = BAGGAGE;
          }
        default:
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
                traceId = DDId.from(firstValue);
                break;
              case SPAN_ID:
                spanId = DDId.from(firstValue);
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
                  if (baggage.isEmpty()) {
                    baggage = new TreeMap<>();
                  }
                  baggage.put(
                      lowerCaseKey.substring(BAGGAGE_PREFIX_LC.length()), HttpCodec.decode(value));
                  break;
                }
            }
          }
        } catch (RuntimeException e) {
          invalidateContext();
          log.error("Exception when extracting context", e);
          return false;
        }
      }
      return true;
    }

    @Override
    protected int defaultSamplingPriority() {
      return PrioritySampling.SAMPLER_KEEP;
    }
  }
}
