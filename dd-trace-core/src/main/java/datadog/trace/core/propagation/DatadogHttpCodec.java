package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;
import static datadog.trace.core.propagation.XRayHttpCodec.XRayContextInterpreter.handleXRayTraceHeader;
import static datadog.trace.core.propagation.XRayHttpCodec.X_AMZN_TRACE_ID;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.context.propagation.CarrierKeys;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.HeaderSchema;
import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.api.llmobs.LLMObsInternal;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags.HeaderType;
import java.util.Map;
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

  public static HttpCodec.Injector newInjector(Map<String, String> invertedBaggageMapping) {
    return new Injector(invertedBaggageMapping);
  }

  private static class Injector implements HttpCodec.Injector {

    private final Map<String, String> invertedBaggageMapping;

    public Injector(Map<String, String> invertedBaggageMapping) {
      assert invertedBaggageMapping != null;
      this.invertedBaggageMapping = invertedBaggageMapping;
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
      long e2eStart = context.getEndToEndStartTime();
      if (e2eStart > 0) {
        setter.set(carrier, E2E_START_KEY, Long.toString(NANOSECONDS.toMillis(e2eStart)));
      }

      for (final Map.Entry<String, String> entry : context.baggageItems()) {
        String header = invertedBaggageMapping.get(entry.getKey());
        header = header != null ? header : OT_BAGGAGE_PREFIX + entry.getKey();
        setter.set(carrier, header, HttpCodec.encodeBaggage(entry.getValue()));
      }

      // inject x-datadog-tags
      PropagationTags propagationTags = context.getPropagationTags();
      String datadogTags =
          propagationTags.headerValue(
              HeaderType.DATADOG,
              null,
              LLMObsInternal.propagationValuesFor(context),
              propagationTags.samplingState());
      if (datadogTags != null) {
        setter.set(carrier, DATADOG_TAGS_KEY, datadogTags);
      }
    }
  }

  public static HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(
        traceConfigSupplier, () -> new DatadogContextInterpreter(config));
  }

  private static class DatadogContextInterpreter extends ContextInterpreter {
    /**
     * This style's own headers; the ones every style reads come from {@link ContextInterpreter}.
     */
    private static final CarrierKeys OWN_KEYS =
        CarrierKeys.builder()
            .name(TRACE_ID_KEY)
            .name(SPAN_ID_KEY)
            .name(SAMPLING_PRIORITY_KEY)
            .name(ORIGIN_KEY)
            .name(DATADOG_TAGS_KEY)
            // read when AWS propagation is enabled
            .name(XRayHttpCodec.X_AMZN_TRACE_ID)
            .prefix(OT_BAGGAGE_PREFIX)
            .build();

    @Override
    protected CarrierKeys ownKeys() {
      return OWN_KEYS;
    }

    private static final int TRACE_ID = 0;
    private static final int SPAN_ID = 1;
    private static final int ORIGIN = 2;
    private static final int SAMPLING_PRIORITY = 3;
    private static final int OT_BAGGAGE = 4;
    private static final int E2E_START = 5;
    private static final int DD_TAGS = 6;
    private static final int IGNORE = -1;

    private final boolean isAwsPropagationEnabled;

    /** The raw x-datadog-tags value, parsed in {@link #build()} once the priority is known. */
    private String datadogTags;

    private DatadogContextInterpreter(Config config) {
      super(config);
      isAwsPropagationEnabled = config.isAwsPropagationEnabled();
    }

    @Override
    public TracePropagationStyle style() {
      return DATADOG;
    }

    @Override
    public ContextInterpreter reset(TraceConfig traceConfig) {
      datadogTags = null;
      return super.reset(traceConfig);
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
          } else if (DATADOG_TAGS_KEY.equalsIgnoreCase(key)) {
            classification = DD_TAGS;
          } else {
            handledXForwarding(key, value);
          }
          break;
        case 'f':
          handledForwarding(key, value);
          break;
        case 'u':
          handledUserAgent(key, value);
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
        return handleClassified(classification, key, value, lowerCaseKey);
      }
      return acceptUnclassified(key, value);
    }

    @Override
    protected int classify(HeaderSchema header) {
      switch (header) {
        case X_DATADOG_TRACE_ID:
          return TRACE_ID;
        case X_DATADOG_PARENT_ID:
          return SPAN_ID;
        case X_DATADOG_SAMPLING_PRIORITY:
          return SAMPLING_PRIORITY;
        case X_DATADOG_ORIGIN:
          return ORIGIN;
        case X_DATADOG_TAGS:
          return DD_TAGS;
        case X_AMZN_TRACE_ID:
          return BY_NAME; // read only when AWS propagation is enabled
        default:
          return NOT_OWN_HEADER;
      }
    }

    @Override
    protected boolean acceptClassified(int classification, String key, String value) {
      return handleClassified(classification, key, value, null);
    }

    /**
     * Parses the trace id, parent id and sampling priority straight from a transient view, as
     * {@link #handleClassified} would from the same chars, without making a {@code String}. Values
     * that path would treat differently (several comma-separated values, or a trace id whose text
     * isn't canonical, since {@code DDTraceId} keeps the text it parsed) are copied and take it.
     * The view is parsed here and not kept.
     */
    @Override
    public boolean acceptTransient(HeaderSchema header, String key, CharSequence value) {
      int classification =
          header == null || value == null || LOG_EXTRACT_HEADER_NAMES ? BY_NAME : classify(header);
      if ((classification != TRACE_ID
              && classification != SPAN_ID
              && classification != SAMPLING_PRIORITY)
          || !isParsedAsIs(classification, value)) {
        return accept(header, key, value == null ? null : value.toString());
      }
      int end = value.length();
      try {
        switch (classification) {
          case TRACE_ID:
            traceId = DDTraceId.from(LongStringUtils.parseUnsignedLong(value, 0, end));
            break;
          case SPAN_ID:
            spanId = LongStringUtils.parseUnsignedLong(value, 0, end);
            break;
          default:
            samplingPriority = LongStringUtils.parseInt(value, 0, end);
        }
      } catch (NumberFormatException e) {
        invalidateContext();
        log.debug(EXCLUDE_TELEMETRY, "Ignoring context with invalid {}: {}", key, value.toString());
        return false;
      } catch (RuntimeException e) {
        invalidateContext();
        log.debug("Exception when extracting context", e);
        return false;
      }
      return true;
    }

    /**
     * Whether {@link #handleClassified} would parse {@code value} as is: no comma (so {@code
     * firstHeaderValue} returns it unchanged), and for a trace id, canonical decimal text (no
     * leading {@code +} or zero), which {@code DDTraceId} would print back the same.
     */
    private static boolean isParsedAsIs(int classification, CharSequence value) {
      int length = value.length();
      for (int i = 0; i < length; i++) {
        if (value.charAt(i) == ',') {
          return false;
        }
      }
      if (classification == TRACE_ID && length > 1) {
        char first = value.charAt(0);
        return first != '0' && first != '+';
      }
      return true;
    }

    /** Stores the value of a key classified as one of this style's own. */
    private boolean handleClassified(
        int classification, String key, String value, String lowerCaseKey) {
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
              datadogTags = value;
              break;
            case OT_BAGGAGE:
              addBaggageItem(lowerCaseKey.substring(OT_BAGGAGE_PREFIX.length()), value);
              break;
            default:
          }
        }
      } catch (NumberFormatException e) {
        invalidateContext();
        log.debug(EXCLUDE_TELEMETRY, "Ignoring context with invalid {}: {}", key, value);
        return false;
      } catch (RuntimeException e) {
        invalidateContext();
        log.debug("Exception when extracting context", e);
        return false;
      }
      return true;
    }

    @Override
    protected TagContext build() {
      if (datadogTags != null) {
        try {
          propagationTags =
              propagationTagsFactory.fromDatadogHeaderValue(
                  datadogTags, inheritedSamplingPriority());
        } catch (RuntimeException e) {
          invalidateContext();
          log.debug("Exception when extracting context", e);
        } finally {
          // The interpreter is cached per thread: don't keep the raw header reachable until the
          // next extraction.
          datadogTags = null;
        }
      }
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
