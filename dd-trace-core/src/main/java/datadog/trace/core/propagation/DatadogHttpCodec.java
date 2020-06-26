package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;

import datadog.trace.api.DDId;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** A codec designed for HTTP transport via headers using Datadog headers */
@Slf4j
class DatadogHttpCodec {

  private static final String OT_BAGGAGE_PREFIX = "ot-baggage-";
  private static final String TRACE_ID_KEY = "x-datadog-trace-id";
  private static final String SPAN_ID_KEY = "x-datadog-parent-id";
  private static final String SAMPLING_PRIORITY_KEY = "x-datadog-sampling-priority";
  private static final String ORIGIN_KEY = "x-datadog-origin";

  private static final Set<CharSequence> DD_KEYS =
      new HashSet<CharSequence>(
          Arrays.asList(TRACE_ID_KEY, SPAN_ID_KEY, SAMPLING_PRIORITY_KEY, ORIGIN_KEY));

  private DatadogHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {

      setter.set(carrier, TRACE_ID_KEY, context.getTraceId().toString());
      setter.set(carrier, SPAN_ID_KEY, context.getSpanId().toString());
      if (context.lockSamplingPriority()) {
        setter.set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(context.getSamplingPriority()));
      }
      final String origin = context.getOrigin();
      if (origin != null) {
        setter.set(carrier, ORIGIN_KEY, origin);
      }

      for (final Map.Entry<String, String> entry : context.baggageItems()) {
        setter.set(carrier, OT_BAGGAGE_PREFIX + entry.getKey(), HttpCodec.encode(entry.getValue()));
      }
      log.debug("{} - Datadog parent context injected", context.getTraceId());
    }
  }

  public static HttpCodec.Extractor newExtractor(final Map<String, String> tagMapping) {
    return new TagContextExtractor(
        tagMapping,
        new ContextInterpreter.Factory() {
          @Override
          protected ContextInterpreter construct(Map<String, String> mapping) {
            return new DatadogContextInterpreter(mapping);
          }
        });
  }

  private static class DatadogContextInterpreter extends ContextInterpreter {

    private static final int SPECIAL_HEADERS = 0;
    private static final int TAGS = 1;
    private static final int OT_BAGGAGE = 2;
    private static final int IGNORE = -1;

    private DatadogContextInterpreter(Map<String, String> taggedHeaders) {
      super(taggedHeaders);
    }

    @Override
    public boolean accept(int classification, String lowerCaseKey, String value) {
      try {
        String firstValue = firstHeaderValue(value);
        if (null != firstValue) {
          switch (classification) {
            case SPECIAL_HEADERS:
              {
                switch (lowerCaseKey) {
                  case TRACE_ID_KEY:
                    traceId = DDId.from(firstValue);
                    break;
                  case SPAN_ID_KEY:
                    spanId = DDId.from(firstValue);
                    break;
                  case ORIGIN_KEY:
                    origin = firstValue;
                    break;
                  case SAMPLING_PRIORITY_KEY:
                    samplingPriority = Integer.parseInt(firstValue);
                    break;
                  default:
                    // shouldn't happen
                }
                break;
              }
            case TAGS:
              {
                String mappedKey = taggedHeaders.get(lowerCaseKey);
                if (null != mappedKey) {
                  if (tags.isEmpty()) {
                    tags = new HashMap<>();
                  }
                  tags.put(mappedKey, HttpCodec.decode(value));
                }
                break;
              }
            case OT_BAGGAGE:
              {
                if (baggage.isEmpty()) {
                  baggage = new HashMap<>();
                }
                baggage.put(
                    lowerCaseKey.substring(OT_BAGGAGE_PREFIX.length()), HttpCodec.decode(value));
              }
              break;
          }
        }
      } catch (RuntimeException e) {
        invalidateContext();
        log.error("Exception when extracting context", e);
        return false;
      }
      return true;
    }

    @Override
    public int classify(String key) {
      if (DD_KEYS.contains(key)) {
        return SPECIAL_HEADERS;
      }
      if (taggedHeaders.containsKey(key)) {
        return TAGS;
      }
      if (key.startsWith(OT_BAGGAGE_PREFIX)) {
        return OT_BAGGAGE;
      }
      return IGNORE;
    }
  }
}
