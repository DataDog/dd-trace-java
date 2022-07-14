package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;

import datadog.trace.api.DDId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A codec designed for HTTP transport via headers using B3 headers
 *
 * <p>TODO: there is fair amount of code duplication between DatadogHttpCodec and this class,
 * especially in part where TagContext is handled. We may want to refactor that and avoid special
 * handling of TagContext in other places (i.e. CompoundExtractor).
 */
class B3HttpCodec {

  private static final Logger log = LoggerFactory.getLogger(B3HttpCodec.class);

  private static final String B3_TRACE_ID = "b3.traceid";
  private static final String B3_SPAN_ID = "b3.spanid";
  private static final String TRACE_ID_KEY = "X-B3-TraceId";
  private static final String SPAN_ID_KEY = "X-B3-SpanId";
  private static final String SAMPLING_PRIORITY_KEY = "X-B3-Sampled";
  // See https://github.com/openzipkin/b3-propagation#single-header for b3 header documentation
  private static final String B3_KEY = "b3";
  private static final String SAMPLING_PRIORITY_ACCEPT = String.valueOf(1);
  private static final String SAMPLING_PRIORITY_DROP = String.valueOf(0);

  private B3HttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static final HttpCodec.Injector INJECTOR = new Injector();

  private static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      try {
        final String injectedTraceId = context.getTraceId().toHexStringOrOriginal();
        final String injectedSpanId = context.getSpanId().toHexStringOrOriginal();
        setter.set(carrier, TRACE_ID_KEY, injectedTraceId);
        setter.set(carrier, SPAN_ID_KEY, injectedSpanId);

        final StringBuilder injectedB3Id = new StringBuilder(100);
        injectedB3Id.append(injectedTraceId).append('-').append(injectedSpanId);

        if (context.lockSamplingPriority()) {
          final String injectedSamplingPriority =
              convertSamplingPriority(context.getSamplingPriority());
          setter.set(carrier, SAMPLING_PRIORITY_KEY, injectedSamplingPriority);

          injectedB3Id.append('-').append(injectedSamplingPriority);
        }
        setter.set(carrier, B3_KEY, injectedB3Id.toString());

        log.debug("{} - B3 parent context injected - {}", context.getTraceId(), injectedTraceId);
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
          protected ContextInterpreter construct(final Map<String, String> mapping) {
            return new B3ContextInterpreter(mapping);
          }
        });
  }

  private static class B3ContextInterpreter extends ContextInterpreter {

    private static final int TRACE_ID = 0;
    private static final int SPAN_ID = 1;
    private static final int TAGS = 2;
    private static final int SAMPLING_PRIORITY = 3;
    private static final int B3_ID = 4;
    private static final int IGNORE = -1;

    private B3ContextInterpreter(final Map<String, String> taggedHeaders) {
      super(taggedHeaders);
    }

    @Override
    public boolean accept(final String key, final String value) {
      if (null == key || key.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }
      String lowerCaseKey = null;
      int classification = IGNORE;
      // Prioritize b3 header. If b3 has already propagated traceId, spanId, and Sampling, we won't
      // overwrite those
      if (B3_KEY.equals(key)) {
        classification = B3_ID;
      } else {
        char first = Character.toLowerCase(key.charAt(0));
        switch (first) {
          case 'x':
            if ((traceId == null || traceId == DDId.ZERO) && TRACE_ID_KEY.equalsIgnoreCase(key)) {
              classification = TRACE_ID;
            } else if ((spanId == null || spanId == DDId.ZERO)
                && SPAN_ID_KEY.equalsIgnoreCase(key)) {
              classification = SPAN_ID;
            } else if (samplingPriority == defaultSamplingPriority()
                && SAMPLING_PRIORITY_KEY.equalsIgnoreCase(key)) {
              classification = SAMPLING_PRIORITY;
            } else if (handledXForwarding(key, value)) {
              return true;
            }
            break;
          case 'f':
            if (handledForwarding(key, value)) {
              return true;
            }
            break;
          case 'u':
            if (handledUserAgent(key, value)) {
              return true;
            }
            break;
          default:
        }
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
      if (classification != IGNORE) {
        try {
          final String firstValue = firstHeaderValue(value);
          if (null != firstValue) {
            switch (classification) {
              case B3_ID:
                if (extractB3(firstValue)) {
                  return true;
                }
                break;
              case TRACE_ID:
                {
                  if (setTraceId(firstValue)) {
                    return true;
                  }
                  break;
                }
              case SPAN_ID:
                setSpanId(firstValue);
                break;
              case SAMPLING_PRIORITY:
                samplingPriority = convertSamplingPriority(firstValue);
                break;
              case TAGS:
                {
                  final String mappedKey = taggedHeaders.get(lowerCaseKey);
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
        } catch (final RuntimeException e) {
          invalidateContext();
          log.debug("Exception when extracting context", e);
          return false;
        }
      }
      return true;
    }

    private boolean extractB3(final String firstValue) {
      if (firstValue.length() == 1) {
        samplingPriority = convertSamplingPriority(firstValue);
      } else {
        final int firstIndex = firstValue.indexOf("-");
        final int secondIndex = firstValue.indexOf("-", firstIndex + 1);
        if (firstIndex != -1) {
          final String b3TraceId = firstValue.substring(0, firstIndex);
          if (setTraceId(b3TraceId)) {
            return true;
          }
        }
        if (secondIndex == -1) {
          final String b3SpanId = firstValue.substring(firstIndex + 1);
          setSpanId(b3SpanId);
        } else {
          final String b3SpanId = firstValue.substring(firstIndex + 1, secondIndex);
          setSpanId(b3SpanId);
          final String b3SamplingId = firstValue.substring(secondIndex + 1);
          samplingPriority = convertSamplingPriority(b3SamplingId);
        }
      }
      return false;
    }

    private void setSpanId(final String sId) {
      spanId = DDId.fromHexWithOriginal(sId);
      if (tags.isEmpty()) {
        tags = new TreeMap<>();
      }
      tags.put(B3_SPAN_ID, sId);
    }

    private boolean setTraceId(final String tId) {
      final int length = tId.length();
      if (length > 32) {
        log.debug("Header {} exceeded max length of 32: {}", TRACE_ID_KEY, tId);
        traceId = DDId.ZERO;
        return true;
      } else {
        traceId = DDId.fromHexTruncatedWithOriginal(tId);
      }
      if (tags.isEmpty()) {
        tags = new TreeMap<>();
      }
      tags.put(B3_TRACE_ID, tId);
      return false;
    }

    private int convertSamplingPriority(final String samplingPriority) {
      return "1".equals(samplingPriority)
          ? PrioritySampling.SAMPLER_KEEP
          : PrioritySampling.SAMPLER_DROP;
    }
  }
}
