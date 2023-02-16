package datadog.trace.core.propagation;

import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTrace128Id;
import datadog.trace.api.DDTrace64Id;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A codec designed for HTTP transport via headers using B3 headers */
class B3HttpCodec {

  private static final Logger log = LoggerFactory.getLogger(B3HttpCodec.class);

  private static final String B3_TRACE_ID = "b3.traceid";
  private static final String B3_SPAN_ID = "b3.spanid";
  static final String TRACE_ID_KEY = "X-B3-TraceId";
  static final String SPAN_ID_KEY = "X-B3-SpanId";
  private static final String SAMPLING_PRIORITY_KEY = "X-B3-Sampled";
  // See https://github.com/openzipkin/b3-propagation#single-header for b3 header documentation
  private static final String B3_KEY = "b3";
  private static final String SAMPLING_PRIORITY_ACCEPT = String.valueOf(1);
  private static final String SAMPLING_PRIORITY_DROP = String.valueOf(0);

  private B3HttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static final HttpCodec.Injector INJECTOR =
      new HttpCodec.Injector() {
        @Override
        public <C> void inject(
            DDSpanContext context, C carrier, AgentPropagation.Setter<C> setter) {
          SINGLE_INJECTOR.inject(context, carrier, setter);
          MULTI_INJECTOR.inject(context, carrier, setter);
        }
      };

  public static final HttpCodec.Injector MULTI_INJECTOR = new B3MultiInjector();

  public static final HttpCodec.Injector SINGLE_INJECTOR = new B3SingleInjector();

  private static class B3MultiInjector implements HttpCodec.Injector {
    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      final String injectedTraceId = context.getTraceId().toHexString();
      final String injectedSpanId = DDSpanId.toHexString(context.getSpanId());
      setter.set(carrier, TRACE_ID_KEY, injectedTraceId);
      setter.set(carrier, SPAN_ID_KEY, injectedSpanId);
      if (context.lockSamplingPriority()) {
        final String injectedSamplingPriority =
            convertSamplingPriority(context.getSamplingPriority());
        setter.set(carrier, SAMPLING_PRIORITY_KEY, injectedSamplingPriority);
      }
      log.debug("{} - B3 parent context injected - {}", context.getTraceId(), injectedTraceId);
    }
  }

  private static class B3SingleInjector implements HttpCodec.Injector {
    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      final String injectedTraceId = context.getTraceId().toHexString();
      final String injectedSpanId = DDSpanId.toHexString(context.getSpanId());
      final StringBuilder injectedB3Id = new StringBuilder(100);
      injectedB3Id.append(injectedTraceId).append('-').append(injectedSpanId);

      if (context.lockSamplingPriority()) {
        final String injectedSamplingPriority =
            convertSamplingPriority(context.getSamplingPriority());
        injectedB3Id.append('-').append(injectedSamplingPriority);
      }
      setter.set(carrier, B3_KEY, injectedB3Id.toString());
      log.debug("{} - B3 parent context injected - {}", context.getTraceId(), injectedTraceId);
    }
  }

  private static String convertSamplingPriority(final int samplingPriority) {
    return samplingPriority > 0 ? SAMPLING_PRIORITY_ACCEPT : SAMPLING_PRIORITY_DROP;
  }

  // Only used from tests
  static HttpCodec.Extractor newExtractor(
      final Map<String, String> tagMapping, Map<String, String> baggageMapping) {
    Config config = Config.get();
    final List<HttpCodec.Extractor> extractors = new ArrayList<>(2);
    extractors.add(newSingleExtractor(tagMapping, baggageMapping, config));
    extractors.add(newMultiExtractor(tagMapping, baggageMapping, config));
    return new HttpCodec.CompoundExtractor(extractors);
  }

  public static HttpCodec.Extractor newMultiExtractor(
      final Map<String, String> tagMapping,
      Map<String, String> baggageMapping,
      final Config config) {
    return new TagContextExtractor(
        tagMapping,
        baggageMapping,
        new ContextInterpreter.Factory() {
          @Override
          protected ContextInterpreter construct(
              final Map<String, String> mapping, Map<String, String> baggageMapping) {
            return new B3MultiContextInterpreter(mapping, baggageMapping, config);
          }
        });
  }

  public static HttpCodec.Extractor newSingleExtractor(
      final Map<String, String> tagMapping,
      Map<String, String> baggageMapping,
      final Config config) {
    return new TagContextExtractor(
        tagMapping,
        baggageMapping,
        new ContextInterpreter.Factory() {
          @Override
          protected ContextInterpreter construct(
              final Map<String, String> mapping, Map<String, String> baggageMapping) {
            return new B3SingleContextInterpreter(mapping, baggageMapping, config);
          }
        });
  }

  private abstract static class B3BaseContextInterpreter extends ContextInterpreter {
    public B3BaseContextInterpreter(
        Map<String, String> taggedHeaders, Map<String, String> baggageMapping, Config config) {
      super(taggedHeaders, baggageMapping, config);
    }

    protected void setSpanId(final String sId) {
      spanId = DDSpanId.fromHex(sId);
      if (tags.isEmpty()) {
        tags = new TreeMap<>();
      }
      tags.put(B3_SPAN_ID, sId);
    }

    protected boolean setTraceId(final String tId) {
      final int length = tId.length();
      if (length > 32) {
        log.debug("Header {} exceeded max length of 32: {}", TRACE_ID_KEY, tId);
        traceId = DDTraceId.ZERO;
        return false;
      } else if (length == 32) {
        traceId = DDTrace128Id.from(tId);
      } else if (length == 16) {
        traceId = DDTrace64Id.fromHex(tId);
      }
      if (tags.isEmpty()) {
        tags = new TreeMap<>();
      }
      tags.put(B3_TRACE_ID, tId);
      return true;
    }
  }

  private static final class B3MultiContextInterpreter extends B3BaseContextInterpreter {
    private B3MultiContextInterpreter(
        final Map<String, String> taggedHeaders,
        Map<String, String> baggageMapping,
        Config config) {
      super(taggedHeaders, baggageMapping, config);
    }

    @Override
    public boolean accept(final String key, final String value) {
      if (null == key || key.isEmpty() || null == value || value.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }
      try {
        char first = Character.toLowerCase(key.charAt(0));
        switch (first) {
          case 'x':
            if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
              setTraceId(firstHeaderValue(value));
              return true;
            } else if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
              setSpanId(firstHeaderValue(value));
              return true;
            } else if (SAMPLING_PRIORITY_KEY.equalsIgnoreCase(key)) {
              samplingPriority = convertSamplingPriority(firstHeaderValue(value));
              return true;
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
        if (handledIpHeaders(key, value)) {
          return true;
        }
        handleTags(key, value);
      } catch (RuntimeException e) {
        invalidateContext();
        log.debug("Exception when extracting context", e);
        return false;
      }
      return true;
    }
  }

  private static final class B3SingleContextInterpreter extends B3BaseContextInterpreter {
    public B3SingleContextInterpreter(
        Map<String, String> taggedHeaders, Map<String, String> baggageMapping, Config config) {
      super(taggedHeaders, baggageMapping, config);
    }

    @Override
    public boolean accept(String key, String value) {
      try {
        if (null == key || key.isEmpty() || null == value || value.isEmpty()) {
          return true;
        }
        if (LOG_EXTRACT_HEADER_NAMES) {
          log.debug("Header: {}", key);
        }
        if (B3_KEY.equals(key)) {
          return extractB3(firstHeaderValue(value));
        } else {
          char first = Character.toLowerCase(key.charAt(0));
          switch (first) {
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
            case 'u':
              if (handledUserAgent(key, value)) {
                return true;
              }
              break;
          }
        }
        if (handledIpHeaders(key, value)) {
          return true;
        }
        handleTags(key, value);
      } catch (RuntimeException e) {
        invalidateContext();
        log.debug("Exception when extracting context", e);
        return false;
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
          if (!setTraceId(b3TraceId)) {
            return false;
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
      return true;
    }
  }

  private static int convertSamplingPriority(final String samplingPriority) {
    return "1".equals(samplingPriority)
        ? PrioritySampling.SAMPLER_KEEP
        : PrioritySampling.SAMPLER_DROP;
  }
}
