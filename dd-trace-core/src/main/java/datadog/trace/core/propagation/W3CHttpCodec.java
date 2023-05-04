package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A codec designed for HTTP transport via headers using W3C traceparent and tracestate headers */
class W3CHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(W3CHttpCodec.class);

  static final String TRACE_PARENT_KEY = "traceparent";
  static final String TRACE_STATE_KEY = "tracestate";
  static final String OT_BAGGAGE_PREFIX = "ot-baggage-";
  private static final String E2E_START_KEY = OT_BAGGAGE_PREFIX + DDTags.TRACE_START_TIME;

  private static final int TRACE_PARENT_TID_START = 2 + 1;
  private static final int TRACE_PARENT_TID_END = TRACE_PARENT_TID_START + 32;
  private static final int TRACE_PARENT_SID_START = TRACE_PARENT_TID_END + 1;
  private static final int TRACE_PARENT_SID_END = TRACE_PARENT_SID_START + 16;
  private static final int TRACE_PARENT_FLAGS_START = TRACE_PARENT_SID_END + 1;
  private static final int TRACE_PARENT_FLAGS_SAMPLED = 1;
  private static final int TRACE_PARENT_LENGTH = TRACE_PARENT_FLAGS_START + 2;

  private W3CHttpCodec() {
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
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      StringBuilder sb = new StringBuilder(TRACE_PARENT_LENGTH);
      sb.append("00-");
      sb.append(context.getTraceId().toHexString());
      sb.append("-");
      sb.append(DDSpanId.toHexStringPadded(context.getSpanId()));
      sb.append(context.getSamplingPriority() > 0 ? "-01" : "-00");
      setter.set(carrier, TRACE_PARENT_KEY, sb.toString());
      String tracestate = context.getPropagationTags().headerValue(PropagationTags.HeaderType.W3C);
      if (tracestate != null && !tracestate.isEmpty()) {
        setter.set(carrier, TRACE_STATE_KEY, tracestate);
      }

      long e2eStart = context.getEndToEndStartTime();
      if (e2eStart > 0) {
        setter.set(carrier, E2E_START_KEY, Long.toString(NANOSECONDS.toMillis(e2eStart)));
      }

      for (final Map.Entry<String, String> entry : context.baggageItems()) {
        String header = invertedBaggageMapping.get(entry.getKey());
        header = header != null ? header : OT_BAGGAGE_PREFIX + entry.getKey();
        setter.set(carrier, header, HttpCodec.encode(entry.getValue()));
      }
    }
  }

  public static HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(traceConfigSupplier, () -> new W3CContextInterpreter(config));
  }

  private static class W3CContextInterpreter extends ContextInterpreter {

    private static final int TRACE_PARENT = 0;
    private static final int TRACE_STATE = 1;
    private static final int OT_BAGGAGE = 2;
    private static final int E2E_START = 3;
    private static final int IGNORE = -1;

    private final PropagationTags.Factory datadogTagsFactory;

    // We need to delay handling of the tracestate header until after traceparent
    private String tracestateHeader = null;
    private String traceparentHeader = null;

    private W3CContextInterpreter(Config config) {
      super(config);
      datadogTagsFactory = PropagationTags.factory(config);
    }

    @Override
    public ContextInterpreter reset(TraceConfig traceConfig) {
      tracestateHeader = null;
      traceparentHeader = null;
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
        case 'f':
          if (handledForwarding(key, value)) {
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
        case 't':
          if (TRACE_PARENT_KEY.equalsIgnoreCase(key)) {
            classification = TRACE_PARENT;
          } else if (TRACE_STATE_KEY.equalsIgnoreCase(key)) {
            classification = TRACE_STATE;
          }
          break;
        case 'u':
          if (handledUserAgent(key, value)) {
            return true;
          }
          break;
        case 'x':
          if (handledXForwarding(key, value)) {
            return true;
          }
          break;
        default:
      }

      if (classification != IGNORE) {
        try {
          if (null != value) {
            switch (classification) {
              case TRACE_PARENT:
                return storeTraceParent(value);
              case TRACE_STATE:
                return storeTraceState(value);
              case E2E_START:
                endToEndStartTime = extractEndToEndStartTime(firstHeaderValue(value));
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

    private long extractEndToEndStartTime(String value) {
      try {
        return MILLISECONDS.toNanos(Long.parseLong(value));
      } catch (RuntimeException e) {
        log.debug("Ignoring invalid end-to-end start time {}", value, e);
        return 0;
      }
    }

    private boolean storeTraceParent(String value) {
      String trimmed = trim(value);
      if (traceparentHeader != null) {
        // We should not accept multiple traceparent headers
        if (log.isDebugEnabled()) {
          log.debug(
              "Multiple traceparent headers. Had '{}' and got '{}'", traceparentHeader, trimmed);
        }
        onlyTagContext();
      } else {
        traceparentHeader = trimmed;
      }
      return true;
    }

    private boolean storeTraceState(String value) {
      String trimmed = trim(value);
      if (!trimmed.isEmpty()) {
        // Yes, this is not efficient for multiple headers, but that is hopefully not common
        tracestateHeader = tracestateHeader == null ? trimmed : tracestateHeader + "," + trimmed;
      }
      return true;
    }

    /**
     * We need to delay handling of the W3C headers until the end since tracestate headers should be
     * concatenated before processed, and only if there is a single valid traceparent header present
     */
    @Override
    protected TagContext build() {
      // If there is neither a traceparent or a tracestate header, then ignore this
      if (traceparentHeader == null && tracestateHeader == null) {
        onlyTagContext();
      }
      if (valid && fullContext) {
        try {
          if (traceparentHeader == null && tracestateHeader != null) {
            throw new IllegalStateException(
                "Found no traceparent header but tracestate header '" + tracestateHeader + "'");
          }
          // Now we know that we have at least a traceparent header
          parseTraceParentHeader(traceparentHeader, this);
          parseTraceStateHeader(tracestateHeader, this, datadogTagsFactory);
        } catch (RuntimeException e) {
          onlyTagContext();
          log.debug("Exception when building context", e);
        }
      }
      return super.build();
    }

    static void parseTraceParentHeader(String tp, ContextInterpreter context) {
      int length = tp == null ? 0 : tp.length();
      if (length < TRACE_PARENT_LENGTH) {
        throw new IllegalStateException("The length of traceparent '" + tp + "' is too short");
      }
      long version = LongStringUtils.parseUnsignedLongHex(tp, 0, 2, true);
      if (version == 255) {
        throw new IllegalStateException("Illegal version number " + tp.substring(0, 2));
      } else if (version == 0 && length > TRACE_PARENT_LENGTH) {
        throw new IllegalStateException("The length of traceparent '" + tp + "' is too long");
      }
      DDTraceId traceId = DD128bTraceId.fromHex(tp, TRACE_PARENT_TID_START, 32, true);
      if (traceId.toLong() == 0) {
        throw new IllegalStateException(
            "Illegal all zero 64 bit trace id "
                + tp.substring(TRACE_PARENT_TID_START, TRACE_PARENT_TID_END));
      }
      context.traceId = traceId;
      context.spanId = DDSpanId.fromHex(tp, TRACE_PARENT_SID_START, 16, true);
      if (context.spanId == 0) {
        throw new IllegalStateException(
            "Illegal all zero span id "
                + tp.substring(TRACE_PARENT_SID_START, TRACE_PARENT_SID_END));
      }
      if (version != 0 && length > TRACE_PARENT_LENGTH && tp.charAt(TRACE_PARENT_LENGTH) != '-') {
        throw new IllegalStateException("Illegal character after flags in '" + tp + "'");
      }
      long flags = LongStringUtils.parseUnsignedLongHex(tp, TRACE_PARENT_FLAGS_START, 2, true);
      if ((flags & TRACE_PARENT_FLAGS_SAMPLED) != 0) {
        context.samplingPriority = SAMPLER_KEEP;
      } else {
        context.samplingPriority = SAMPLER_DROP;
      }
    }

    static void parseTraceStateHeader(
        String ts, ContextInterpreter context, PropagationTags.Factory factory) {
      if (ts == null || ts.isEmpty()) {
        context.propagationTags = factory.empty();
      } else {
        context.propagationTags = factory.fromHeaderValue(PropagationTags.HeaderType.W3C, ts);
      }
      int ptagsPriority = context.propagationTags.getSamplingPriority();
      int contextPriority = context.samplingPriority;
      if ((contextPriority == SAMPLER_DROP && ptagsPriority > 0)
          || (contextPriority == SAMPLER_KEEP && ptagsPriority <= 0)
          || ptagsPriority == PrioritySampling.UNSET) {
        // Override Datadog sampling priority with W3C one
        context.propagationTags.updateTraceSamplingPriority(
            contextPriority, SamplingMechanism.EXTERNAL_OVERRIDE);
      } else {
        // Use more detailed Datadog sampling priority in context
        context.samplingPriority = ptagsPriority;
      }
      // Use the origin
      context.origin = context.propagationTags.getOrigin();
      // Ensure TraceId high-order bits match
      context.propagationTags.updateTraceIdHighOrderBits(context.traceId.toHighOrderLong());
    }

    private static String trim(String input) {
      if (input == null) {
        return "";
      }
      final int last = input.length() - 1;
      if (last == 0) {
        return input;
      }
      int start;
      for (start = 0; start <= last; start++) {
        char c = input.charAt(start);
        if (c != '\t' && c != ' ') {
          break;
        }
      }
      int end;
      for (end = last; end > start; end--) {
        char c = input.charAt(end);
        if (c != '\t' && c != ' ') {
          break;
        }
      }
      if (start == 0 && end == last) {
        return input;
      } else {
        return input.substring(start, end + 1);
      }
    }
  }
}
