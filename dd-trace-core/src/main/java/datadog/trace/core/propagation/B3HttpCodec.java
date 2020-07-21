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

  private B3HttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      try {
        setter.set(carrier, TRACE_ID_KEY, context.getTraceId().toHexString().toLowerCase());
        setter.set(carrier, SPAN_ID_KEY, context.getSpanId().toHexString().toLowerCase());

        if (context.lockSamplingPriority()) {
          setter.set(
              carrier,
              SAMPLING_PRIORITY_KEY,
              convertSamplingPriority(context.getSamplingPriority()));
        }
        log.debug("{} - B3 parent context injected", context.getTraceId());
      } catch (final NumberFormatException e) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Cannot parse context id(s): {} {}", context.getTraceId(), context.getSpanId(), e);
        }
      }
    }

    private String convertSamplingPriority(final int samplingPriority) {
      return samplingPriority > 0 ? SAMPLING_PRIORITY_ACCEPT : SAMPLING_PRIORITY_DROP;
    }
  }

  public static HttpCodec.Extractor newExtractor(final Map<String, String> tagMapping) {
    return new TagContextExtractor(
        tagMapping,
        new ContextInterpreter.Factory() {
          @Override
          protected ContextInterpreter construct(Map<String, String> mapping) {
            return new B3ContextInterpreter(mapping);
          }
        });
  }

  private static class B3ContextInterpreter extends ContextInterpreter {

    private static final int TRACE_ID = 0;
    private static final int SPAN_ID = 1;
    private static final int TAGS = 2;
    private static final int SAMPLING_PRIORITY = 3;
    private static final int IGNORE = -1;

    private B3ContextInterpreter(Map<String, String> taggedHeaders) {
      super(taggedHeaders);
    }

    @Override
    public boolean accept(String key, String value) {
      String lowerCaseKey = null;
      int classification = IGNORE;
      if (Character.toLowerCase(key.charAt(0)) == 'x') {
        if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
          classification = TRACE_ID;
        } else if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
          classification = SPAN_ID;
        } else if (SAMPLING_PRIORITY_KEY.equalsIgnoreCase(key)) {
          classification = SAMPLING_PRIORITY;
        }
      }
      if (!taggedHeaders.isEmpty() && classification == IGNORE) {
        lowerCaseKey = toLowerCase(key);
        if (taggedHeaders.containsKey(lowerCaseKey)) {
          classification = TAGS;
        }
      }
      if (classification != IGNORE) {
        try {
          String firstValue = firstHeaderValue(value);
          if (null != firstValue) {
            switch (classification) {
              case TRACE_ID:
                {
                  final String trimmedValue;
                  final int length = firstValue.length();
                  if (length > 32) {
                    log.debug("Header {} exceeded max length of 32: {}", TRACE_ID_KEY, value);
                    traceId = DDId.ZERO;
                    return true;
                  } else if (length > 16) {
                    trimmedValue = value.substring(length - 16);
                  } else {
                    trimmedValue = value;
                  }
                  traceId = DDId.fromHex(trimmedValue);
                  break;
                }
              case SPAN_ID:
                spanId = DDId.fromHex(firstValue);
                break;
              case SAMPLING_PRIORITY:
                samplingPriority = convertSamplingPriority(firstValue);
                break;
              case TAGS:
                {
                  String mappedKey = taggedHeaders.get(lowerCaseKey);
                  if (null != mappedKey) {
                    if (tags.isEmpty()) {
                      tags = new TreeMap<>();
                    }
                    tags.put(mappedKey, HttpCodec.decode(firstValue));
                  }
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

    private int convertSamplingPriority(final String samplingPriority) {
      return "1".equals(samplingPriority)
          ? PrioritySampling.SAMPLER_KEEP
          : PrioritySampling.SAMPLER_DROP;
    }
  }
}
