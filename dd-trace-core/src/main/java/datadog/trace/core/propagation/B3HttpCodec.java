package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.B3MULTI;
import static datadog.trace.api.TracePropagationStyle.B3SINGLE;
import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.DDSpanContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
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

  public static HttpCodec.Injector newCombinedInjector(boolean paddingEnabled) {
    return new HttpCodec.CompoundInjector(
        Arrays.asList(newSingleInjector(paddingEnabled), newMultiInjector(paddingEnabled)));
  }

  public static HttpCodec.Injector newMultiInjector(boolean paddingEnalbed) {
    return new B3MultiInjector(paddingEnalbed);
  }

  public static HttpCodec.Injector newSingleInjector(boolean paddingEnabled) {
    return new B3SingleInjector(paddingEnabled);
  }

  private abstract static class B3Injector implements HttpCodec.Injector {
    private final boolean paddingEnabled;

    public B3Injector(boolean paddingEnabled) {
      this.paddingEnabled = paddingEnabled;
    }

    /**
     * Get the TraceId {@link String} representation to inject according the following logic:
     *
     * <ul>
     *   <li>Returns a 32 lower-case hexadecimal character if padding is enabled or for 128-bit
     *       TraceIds,
     *   <li>Returns the original String representation if the trace was parsed from B3 extractor,
     *   <li>Return a non-padded lower-case hexadecimal String for remaining 64-bit TraceIds.
     * </ul>
     *
     * @param context The context to get the TraceId from.
     * @return The TraceId {@link String} representation to inject.
     */
    protected final String getInjectedTraceId(DDSpanContext context) {
      DDTraceId traceId = context.getTraceId();
      if (this.paddingEnabled || traceId instanceof DD128bTraceId) {
        return traceId.toHexString();
      } else if (traceId instanceof B3TraceId) {
        return ((B3TraceId) traceId).getOriginal();
      } else {
        return DDSpanId.toHexString(traceId.toLong());
      }
    }

    /**
     * Get the SpanId {@link String} representation to inject according the following logic:
     *
     * <ul>
     *   <li>Returns a 16 lower-case hexadecimal character if padding is enabled,
     *   <li>Returns a non-padded lower-case hexadecimal character otherwise.
     * </ul>
     *
     * @param context The context to get the SpanId from.
     * @return The SpanId {@link String} representation to inject.
     */
    protected final String getInjectedSpanId(DDSpanContext context) {
      long spanId = context.getSpanId();
      if (this.paddingEnabled) {
        return DDSpanId.toHexStringPadded(spanId);
      } else {
        return DDSpanId.toHexString(spanId);
      }
    }
  }

  private static final class B3MultiInjector extends B3Injector {
    public B3MultiInjector(boolean paddingEnabled) {
      super(paddingEnabled);
    }

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final CarrierSetter<C> setter) {
      final String injectedTraceId = getInjectedTraceId(context);
      final String injectedSpanId = getInjectedSpanId(context);
      setter.set(carrier, TRACE_ID_KEY, injectedTraceId);
      setter.set(carrier, SPAN_ID_KEY, injectedSpanId);
      if (context.lockSamplingPriority()) {
        final String injectedSamplingPriority =
            convertSamplingPriority(context.getSamplingPriority());
        setter.set(carrier, SAMPLING_PRIORITY_KEY, injectedSamplingPriority);
      }
      log.debug(
          "{} - B3 parent context injected - {} {}",
          context.getTraceId(),
          injectedTraceId,
          injectedSpanId);
    }
  }

  private static final class B3SingleInjector extends B3Injector {
    public B3SingleInjector(boolean paddingEnabled) {
      super(paddingEnabled);
    }

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final CarrierSetter<C> setter) {
      final String injectedTraceId = getInjectedTraceId(context);
      final String injectedSpanId = getInjectedSpanId(context);
      final StringBuilder injectedB3IdBuilder = new StringBuilder(100);
      injectedB3IdBuilder.append(injectedTraceId).append('-').append(injectedSpanId);

      if (context.lockSamplingPriority()) {
        final String injectedSamplingPriority =
            convertSamplingPriority(context.getSamplingPriority());
        injectedB3IdBuilder.append('-').append(injectedSamplingPriority);
      }
      String injectedB3Id = injectedB3IdBuilder.toString();
      setter.set(carrier, B3_KEY, injectedB3Id);
      log.debug("{} - B3 parent context injected - {}", context.getTraceId(), injectedB3Id);
    }
  }

  private static String convertSamplingPriority(final int samplingPriority) {
    return samplingPriority > 0 ? SAMPLING_PRIORITY_ACCEPT : SAMPLING_PRIORITY_DROP;
  }

  // Only used from tests
  static HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    final List<HttpCodec.Extractor> extractors = new ArrayList<>(2);
    extractors.add(newSingleExtractor(config, traceConfigSupplier));
    extractors.add(newMultiExtractor(config, traceConfigSupplier));
    return new HttpCodec.CompoundExtractor(extractors, config.isTracePropagationExtractFirst());
  }

  public static HttpCodec.Extractor newMultiExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(
        traceConfigSupplier, () -> new B3MultiContextInterpreter(config));
  }

  public static HttpCodec.Extractor newSingleExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(
        traceConfigSupplier, () -> new B3SingleContextInterpreter(config));
  }

  private abstract static class B3BaseContextInterpreter extends ContextInterpreter {
    public B3BaseContextInterpreter(Config config) {
      super(config);
    }

    protected void setSpanId(final String sId) {
      spanId = DDSpanId.fromHex(sId);
      tagLedger().set(B3_SPAN_ID, sId);
    }

    protected boolean setTraceId(final String tId) {
      final int length = tId.length();
      if (length > 32) {
        log.debug("Header {} exceeded max length of 32: {}", TRACE_ID_KEY, tId);
        traceId = DDTraceId.ZERO;
        return false;
      } else {
        B3TraceId b3TraceId = B3TraceId.fromHex(tId);
        traceId = b3TraceId.toLong() == 0 ? DDTraceId.ZERO : b3TraceId;
      }
      tagLedger().set(B3_TRACE_ID, tId);
      return true;
    }
  }

  private static final class B3MultiContextInterpreter extends B3BaseContextInterpreter {
    private B3MultiContextInterpreter(Config config) {
      super(config);
    }

    @Override
    public TracePropagationStyle style() {
      return B3MULTI;
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
    public B3SingleContextInterpreter(Config config) {
      super(config);
    }

    @Override
    public TracePropagationStyle style() {
      return B3SINGLE;
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
        final int firstIndex = firstValue.indexOf('-');
        final int secondIndex = firstValue.indexOf('-', firstIndex + 1);
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
