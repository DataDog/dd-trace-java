package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;
import static datadog.trace.core.propagation.XRayHttpCodec.XRayContextInterpreter.handleXRayTraceHeader;
import static datadog.trace.core.propagation.XRayHttpCodec.X_AMZN_TRACE_ID;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags.HeaderType;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A codec designed for HTTP transport via headers using Datadog headers */
class DatadogHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(DatadogHttpCodec.class);

  static final String OT_BAGGAGE_PREFIX = "ot-baggage-";
  static final String TRACE_ID_KEY = "x-datadog-trace-id";
  static final String SPAN_ID_KEY = "x-datadog-parent-id";
  static final String SAMPLING_PRIORITY_KEY = "x-datadog-sampling-priority";
  static final String ORIGIN_KEY = "x-datadog-origin";
  private static final String E2E_START_KEY = OT_BAGGAGE_PREFIX + DDTags.TRACE_START_TIME;
  static final String DATADOG_TAGS_KEY = "x-datadog-tags";

  private DatadogHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static HttpCodec.Injector newInjector(
      Map<String, String> invertedBaggageMapping, boolean isInjectOtBaggage) {
    return new Injector(invertedBaggageMapping, isInjectOtBaggage);
  }

  private static class Injector implements HttpCodec.Injector {

    private final Map<String, String> invertedBaggageMapping;
    private final boolean isInjectOtBaggage;

    public Injector(Map<String, String> invertedBaggageMapping, boolean isInjectOtBaggage) {
      assert invertedBaggageMapping != null;
      this.invertedBaggageMapping = invertedBaggageMapping;
      this.isInjectOtBaggage = isInjectOtBaggage;
    }

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final CarrierSetter<C> setter) {

      setter.set(carrier, TRACE_ID_KEY, context.getTraceId().toString());
      setter.set(carrier, SPAN_ID_KEY, DDSpanId.toString(context.getSpanId()));
      if (context.lockSamplingPriority()) {
        setter.set(carrier, SAMPLING_PRIORITY_KEY, String.valueOf(context.getSamplingPriority()));
      }
      final CharSequence origin = context.getOrigin();
      if (origin != null) {
        setter.set(carrier, ORIGIN_KEY, origin.toString());
      }
      if (isInjectOtBaggage) {
        injectBaggage(context, carrier, setter);
      }
      // inject x-datadog-tags
      String datadogTags = context.getPropagationTags().headerValue(HeaderType.DATADOG);
      if (datadogTags != null) {
        setter.set(carrier, DATADOG_TAGS_KEY, datadogTags);
      }
    }

    private <C> void injectBaggage(DDSpanContext context, C carrier, CarrierSetter<C> setter) {
      long e2eStart = context.getEndToEndStartTime();
      if (e2eStart > 0) {
        setter.set(carrier, E2E_START_KEY, Long.toString(NANOSECONDS.toMillis(e2eStart)));
      }

      for (final Map.Entry<String, String> entry : context.baggageItems()) {
        String header = invertedBaggageMapping.get(entry.getKey());
        header = header != null ? header : OT_BAGGAGE_PREFIX + entry.getKey();
        setter.set(carrier, header, HttpCodec.encodeBaggage(entry.getValue()));
      }
    }
  }

  public static HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(
        traceConfigSupplier, () -> new DatadogContextInterpreter(config));
  }

  private static class DatadogContextInterpreter extends ContextInterpreter {

    private static final int TRACE_ID = 0;
    private static final int SPAN_ID = 1;
    private static final int ORIGIN = 2;
    private static final int SAMPLING_PRIORITY = 3;
    private static final int OT_BAGGAGE = 4;
    private static final int E2E_START = 5;
    private static final int DD_TAGS = 6;
    private static final int IGNORE = -1;

    private final boolean isAwsPropagationEnabled;

    private DatadogContextInterpreter(Config config) {
      super(config);
      isAwsPropagationEnabled = config.isAwsPropagationEnabled();
    }

    @Override
    public TracePropagationStyle style() {
      return DATADOG;
    }

    @Override
    public boolean accept(String key, String value) {
      if (null == key || key.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }
      String lowerCaseKey = null;
      int classification = IGNORE;
      char first = Character.toLowerCase(key.charAt(0));
      switch (first) {
        case 'x':
          if (TRACE_ID_KEY.equalsIgnoreCase(key)) {
            classification = TRACE_ID;
          } else if (SPAN_ID_KEY.equalsIgnoreCase(key)) {
            classification = SPAN_ID;
          } else if (SAMPLING_PRIORITY_KEY.equalsIgnoreCase(key)) {
            classification = SAMPLING_PRIORITY;
          } else if (ORIGIN_KEY.equalsIgnoreCase(key)) {
            classification = ORIGIN;
          } else if (isAwsPropagationEnabled && X_AMZN_TRACE_ID.equalsIgnoreCase(key)) {
            handleXRayTraceHeader(this, value);
            return true;
          } else if (handledXForwarding(key, value)) {
            return true;
          } else if (DATADOG_TAGS_KEY.equalsIgnoreCase(key)) {
            classification = DD_TAGS;
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
        case 'o':
          lowerCaseKey = toLowerCase(key);
          if (E2E_START_KEY.equals(lowerCaseKey)) {
            classification = E2E_START;
          } else if (lowerCaseKey.startsWith(OT_BAGGAGE_PREFIX)) {
            classification = OT_BAGGAGE;
          }
          break;
        default:
      }

      if (classification != IGNORE) {
        try {
          if (null != value) {
            switch (classification) {
              case TRACE_ID:
                traceId = DDTraceId.from(firstHeaderValue(value));
                break;
              case SPAN_ID:
                spanId = DDSpanId.from(firstHeaderValue(value));
                break;
              case ORIGIN:
                origin = firstHeaderValue(value);
                break;
              case SAMPLING_PRIORITY:
                samplingPriority = Integer.parseInt(firstHeaderValue(value));
                break;
              case E2E_START:
                endToEndStartTime = extractEndToEndStartTime(firstHeaderValue(value));
                break;
              case DD_TAGS:
                propagationTags = propagationTagsFactory.fromHeaderValue(HeaderType.DATADOG, value);
                break;
              case OT_BAGGAGE:
                {
                  if (baggage.isEmpty()) {
                    baggage = new TreeMap<>();
                  }
                  baggage.put(
                      lowerCaseKey.substring(OT_BAGGAGE_PREFIX.length()), HttpCodec.decode(value));
                }
                break;
              default:
            }
          }
        } catch (RuntimeException e) {
          invalidateContext();
          log.debug("Exception when extracting context", e);
          return false;
        }
      } else {
        if (handledIpHeaders(key, value)) {
          return true;
        }
        if (handleTags(key, value)) {
          return true;
        }
        handleMappedBaggage(key, value);
      }
      return true;
    }

    @Override
    protected TagContext build() {
      restore128bTraceId();
      return super.build();
    }

    private long extractEndToEndStartTime(String value) {
      try {
        return MILLISECONDS.toNanos(Long.parseLong(value));
      } catch (RuntimeException e) {
        log.debug("Ignoring invalid end-to-end start time {}", value, e);
        return 0;
      }
    }

    private void restore128bTraceId() {
      long highOrderBits;
      // Check if the low-order 64 bits of the TraceId, and propagation tags were parsed
      if (traceId != DDTraceId.ZERO
          && propagationTags != null
          && (highOrderBits = propagationTags.getTraceIdHighOrderBits()) != 0) {
        // Restore the 128-bit TraceId
        traceId = DD128bTraceId.from(highOrderBits, traceId.toLong());
      }
    }
  }
}
