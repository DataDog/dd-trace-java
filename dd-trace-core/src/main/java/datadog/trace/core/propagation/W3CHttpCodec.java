package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.api.internal.util.LongStringUtils.parseUnsignedLongHexOrZero;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.context.propagation.CarrierKeys;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.HeaderSchema;
import datadog.context.propagation.IndexedCarrier;
import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.llmobs.LLMObsInternal;
import datadog.trace.api.propagation.W3CTraceParent;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags.SamplingState;
import datadog.trace.core.propagation.ptags.W3CTracestate;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A codec designed for HTTP transport via headers using W3C traceparent and tracestate headers */
class W3CHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(W3CHttpCodec.class);

  private static final int TRACE_PARENT_TID_START = 2 + 1;
  private static final int TRACE_PARENT_TID_END = TRACE_PARENT_TID_START + 32;
  private static final int TRACE_PARENT_SID_START = TRACE_PARENT_TID_END + 1;
  private static final int TRACE_PARENT_SID_END = TRACE_PARENT_SID_START + 16;
  private static final int TRACE_PARENT_FLAGS_START = TRACE_PARENT_SID_END + 1;
  private static final int TRACE_PARENT_FLAGS_SAMPLED = 1;
  private static final int TRACE_PARENT_LENGTH = TRACE_PARENT_FLAGS_START + 2;

  @VisibleForTesting static final String TRACE_PARENT_KEY = "traceparent";
  @VisibleForTesting static final String TRACE_STATE_KEY = "tracestate";
  @VisibleForTesting static final String OT_BAGGAGE_PREFIX = "ot-baggage-";
  static final String E2E_START_KEY = OT_BAGGAGE_PREFIX + DDTags.TRACE_START_TIME;

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
        final DDSpanContext context, final C carrier, final CarrierSetter<C> setter) {
      PropagationTags propagationTags = context.getPropagationTags();
      SamplingState samplingState = propagationTags.samplingState();
      injectTraceParent(context, samplingState, carrier, setter);
      injectTraceState(context, propagationTags, samplingState, carrier, setter);
      injectBaggage(context, carrier, setter);
    }

    private <C> void injectTraceParent(
        DDSpanContext context, SamplingState samplingState, C carrier, CarrierSetter<C> setter) {
      setter.setAscii(
          carrier,
          TRACE_PARENT_KEY,
          W3CTraceParent.build(
              context.getTraceId(), context.getSpanId(), samplingState.getSamplingPriority() > 0));
    }

    private <C> void injectTraceState(
        DDSpanContext context,
        PropagationTags propagationTags,
        SamplingState samplingState,
        C carrier,
        CarrierSetter<C> setter) {
      // Supply the injecting span's id for the W3C `p:` as a parameter rather than mutating it into
      // the (possibly trace-level, shared) tags — keeps transient per-injection identity out of
      // shared state, so concurrent sibling injects can't race on it.
      String tracestate =
          propagationTags.headerValue(
              W3C,
              DDSpanId.toHexStringPadded(context.getSpanId()),
              LLMObsInternal.propagationValuesFor(context),
              samplingState);
      if (tracestate != null && !tracestate.isEmpty()) {
        setter.set(carrier, TRACE_STATE_KEY, tracestate);
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
    return new TraceContextExtractor(traceConfigSupplier, config);
  }

  /**
   * Whether the trace context's priority, from the {@code traceparent} sampled flag, overrides the
   * tracestate's own Datadog priority ({@code s}).
   */
  static boolean overridesTracestatePriority(int traceparentPriority, int tracestatePriority) {
    return (traceparentPriority == SAMPLER_DROP && tracestatePriority > 0)
        || (traceparentPriority == SAMPLER_KEEP && tracestatePriority <= 0)
        || tracestatePriority == PrioritySampling.UNSET;
  }

  /** What merging a trace context into a context extracted before it reads from it. */
  static final class TraceContextMerge {
    /** The 128-bit trace id from the traceparent, as its high and low order bits. */
    final long traceIdHigh;

    final long traceIdLow;
    final long spanId;
    final W3CTracestate tracestate;

    /** The traceparent priority when it overrides the tracestate's, otherwise UNSET. */
    final int overridingPriority;

    private TraceContextMerge(
        long traceIdHigh,
        long traceIdLow,
        long spanId,
        W3CTracestate tracestate,
        int overridingPriority) {
      this.traceIdHigh = traceIdHigh;
      this.traceIdLow = traceIdLow;
      this.spanId = spanId;
      this.tracestate = tracestate;
      this.overridingPriority = overridingPriority;
    }
  }

  /**
   * {@link TraceContextExtractor#mergeFrom}: the carrier needs the full extraction instead (it has
   * a trace context header without a header constant, or header names are being logged).
   */
  static final TraceContextMerge NEEDS_EXTRACTION =
      new TraceContextMerge(0, 0, 0, null, PrioritySampling.UNSET);

  static final class TraceContextExtractor extends TagContextExtractor {
    private TraceContextExtractor(Supplier<TraceConfig> traceConfigSupplier, Config config) {
      super(traceConfigSupplier, () -> new W3CContextInterpreter(config));
    }

    /**
     * Extracts only what merging into another extracted context needs, without building propagation
     * tags. Returns null where {@link #extract} wouldn't return an {@link ExtractedContext}.
     */
    <C> TraceContextMerge extractForMerge(
        final C carrier, final AgentPropagation.ContextVisitor<C> getter) {
      return ((W3CContextInterpreter) interpret(carrier, getter)).buildForMerge();
    }

    /**
     * {@link #extractForMerge} reading the two trace context headers straight from a carrier that
     * knows each key's header constant, without running this style's interpreter over every key:
     * the same result for the same headers. {@link #NEEDS_EXTRACTION} if this carrier can't be read
     * that way; then use {@link #extractForMerge}, and {@link #extract} rather than {@link
     * #completeExtraction} for the whole context.
     */
    TraceContextMerge mergeFrom(IndexedCarrier carrier) {
      if (ContextInterpreter.LOG_EXTRACT_HEADER_NAMES) {
        return NEEDS_EXTRACTION;
      }
      String traceparent = null;
      String tracestate = null;
      for (int i = 0, n = carrier.size(); i < n; i++) {
        HeaderSchema header = carrier.header(i);
        if (header == HeaderSchema.TRACEPARENT) {
          String value = carrier.value(i);
          if (value != null) {
            if (traceparent != null) {
              // as with the interpreter, multiple traceparent headers mean no context to merge
              return null;
            }
            traceparent = W3CContextInterpreter.trim(value);
          }
        } else if (header == HeaderSchema.TRACESTATE) {
          String value = carrier.value(i);
          if (value != null) {
            String trimmed = W3CContextInterpreter.trim(value);
            if (!trimmed.isEmpty()) {
              tracestate = tracestate == null ? trimmed : tracestate + "," + trimmed;
            }
          }
        } else if (header == null) {
          String key = carrier.key(i);
          if (TRACE_PARENT_KEY.equalsIgnoreCase(key) || TRACE_STATE_KEY.equalsIgnoreCase(key)) {
            return NEEDS_EXTRACTION;
          }
        }
      }
      if (traceparent == null) {
        // no traceparent (with or without a tracestate): nothing to merge, as with the interpreter
        return null;
      }
      // the interpreter's own no-throw parse; the trace id only needs comparing, so it's kept as
      // its parts
      W3CContextInterpreter parser = (W3CContextInterpreter) interpreter();
      if (!parser.parseTraceParent(traceparent, false)) {
        return null;
      }
      W3CTracestate parsed;
      try {
        // merged into a higher-priority context, so only what the merge reads is checked
        parsed = W3CTracestate.parseForLowerPriorityMerge(tracestate);
      } catch (RuntimeException e) {
        log.debug("Exception when building context", e);
        return null;
      }
      int overridingPriority =
          overridesTracestatePriority(parser.samplingPriority, parsed.getSamplingPriority())
              ? parser.samplingPriority
              : PrioritySampling.UNSET;
      return new TraceContextMerge(
          parser.traceIdHigh, parser.traceIdLow, parser.spanId, parsed, overridingPriority);
    }

    /** Builds the whole context that {@link #extractForMerge} last returned the merge parts of. */
    TagContext completeExtraction() {
      return interpreter().build();
    }
  }

  private static class W3CContextInterpreter extends ContextInterpreter {
    /**
     * This style's own headers; the ones every style reads come from {@link ContextInterpreter}.
     */
    private static final CarrierKeys OWN_KEYS =
        CarrierKeys.builder()
            .name(TRACE_PARENT_KEY)
            .name(TRACE_STATE_KEY)
            .prefix(OT_BAGGAGE_PREFIX)
            .build();

    @Override
    protected CarrierKeys ownKeys() {
      return OWN_KEYS;
    }

    private static final int TRACE_PARENT = 0;
    private static final int TRACE_STATE = 1;
    private static final int OT_BAGGAGE = 2;
    private static final int E2E_START = 3;
    private static final int IGNORE = -1;

    // We need to delay handling of the tracestate header until after traceparent
    private String tracestateHeader = null;
    private String traceparentHeader = null;

    /** The high and low order bits of the trace id {@link #parseTraceParent} last parsed. */
    private long traceIdHigh;

    private long traceIdLow;

    private W3CContextInterpreter(Config config) {
      super(config);
    }

    @Override
    public TracePropagationStyle style() {
      return TRACECONTEXT;
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
          handledForwarding(key, value);
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
          handledUserAgent(key, value);
          break;
        case 'x':
          handledXForwarding(key, value);
          break;
        default:
      }

      if (classification != IGNORE) {
        return handleClassified(classification, value, lowerCaseKey);
      }
      return acceptUnclassified(key, value);
    }

    @Override
    protected int classify(HeaderSchema header) {
      switch (header) {
        case TRACEPARENT:
          return TRACE_PARENT;
        case TRACESTATE:
          return TRACE_STATE;
        default:
          return NOT_OWN_HEADER;
      }
    }

    @Override
    protected boolean acceptClassified(int classification, String key, String value) {
      return handleClassified(classification, value, null);
    }

    /** Stores the value of a key classified as one of this style's own. */
    private boolean handleClassified(int classification, String value, String lowerCaseKey) {
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
              addBaggageItem(lowerCaseKey.substring(OT_BAGGAGE_PREFIX.length()), value);
              break;
            default:
          }
        }
      } catch (RuntimeException e) {
        invalidateContext();
        log.debug("Exception when extracting context", e);
        return false;
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
      if (canBuildFullContext()) {
        if (parseRequiredTraceParent(true)) {
          try {
            parseTraceStateHeader(tracestateHeader);
          } catch (RuntimeException e) {
            onlyTagContext();
            log.debug("Exception when building context", e);
          }
        } else {
          onlyTagContext();
        }
      }
      return super.build();
    }

    /** Whether a full context may be built, which needs a traceparent or tracestate header. */
    private boolean canBuildFullContext() {
      if (traceparentHeader == null && tracestateHeader == null) {
        onlyTagContext();
      }
      return valid && fullContext;
    }

    /**
     * Parses the traceparent header, which a tracestate header is invalid without, returning
     * whether it is valid. The trace id is built only if {@code buildTraceId}.
     */
    private boolean parseRequiredTraceParent(boolean buildTraceId) {
      if (traceparentHeader == null) {
        log.debug(
            "Ignoring tracestate header without a traceparent header: '{}'", tracestateHeader);
        return false;
      }
      return parseTraceParent(traceparentHeader, buildTraceId);
    }

    /**
     * Validates the traceparent and reads its parts, returning {@code false} if it is malformed.
     * Malformed traceparent headers are common, so this does not throw: an exception per request is
     * costly. The trace id is built only if {@code buildTraceId}; its high and low order bits are
     * read either way.
     */
    private boolean parseTraceParent(String tp, boolean buildTraceId) {
      int length = tp == null ? 0 : tp.length();
      if (length < TRACE_PARENT_LENGTH) {
        return rejectTraceParent(tp, "too short");
      }
      // zero is also what malformed hex parses to, so a zero part that may be zero is checked
      long version = parseUnsignedLongHexOrZero(tp, 0, 2, true);
      if ((version == 0 && !isAllZeros(tp, 0, 2)) || version == 255) {
        return rejectTraceParent(tp, "illegal version");
      } else if (version == 0 && length > TRACE_PARENT_LENGTH) {
        return rejectTraceParent(tp, "too long");
      }
      this.traceIdHigh = parseUnsignedLongHexOrZero(tp, TRACE_PARENT_TID_START, 16, true);
      if (traceIdHigh == 0 && !isAllZeros(tp, TRACE_PARENT_TID_START, 16)) {
        return rejectTraceParent(tp, "illegal trace id");
      }
      this.traceIdLow = parseUnsignedLongHexOrZero(tp, TRACE_PARENT_TID_START + 16, 16, true);
      if (traceIdLow == 0) {
        return rejectTraceParent(tp, "illegal or all zero 64 bit trace id");
      }
      if (buildTraceId) {
        // Built from its parts, so it formats its hex form only if read, not per extraction
        this.traceId = DD128bTraceId.from(traceIdHigh, traceIdLow);
      }
      this.spanId = parseUnsignedLongHexOrZero(tp, TRACE_PARENT_SID_START, 16, true);
      if (this.spanId == 0) {
        return rejectTraceParent(tp, "illegal or all zero span id");
      }
      if (version != 0 && length > TRACE_PARENT_LENGTH && tp.charAt(TRACE_PARENT_LENGTH) != '-') {
        return rejectTraceParent(tp, "illegal character after flags");
      }
      long flags = parseUnsignedLongHexOrZero(tp, TRACE_PARENT_FLAGS_START, 2, true);
      if (flags == 0 && !isAllZeros(tp, TRACE_PARENT_FLAGS_START, 2)) {
        return rejectTraceParent(tp, "illegal flags");
      }
      if ((flags & TRACE_PARENT_FLAGS_SAMPLED) != 0) {
        this.samplingPriority = SAMPLER_KEEP;
      } else {
        this.samplingPriority = SAMPLER_DROP;
      }
      return true;
    }

    private static boolean rejectTraceParent(String tp, String reason) {
      log.debug("Ignoring invalid traceparent '{}': {}", tp, reason);
      return false;
    }

    private static boolean isAllZeros(String s, int start, int len) {
      for (int i = start, end = start + len; i < end; i++) {
        if (s.charAt(i) != '0') {
          return false;
        }
      }
      return true;
    }

    /** Like {@link #build()}, but for {@link TraceContextExtractor#extractForMerge}. */
    TraceContextMerge buildForMerge() {
      if (!canBuildFullContext()) {
        return null;
      }
      // the trace id only needs to be compared, so it's kept as its parts
      if (!parseRequiredTraceParent(false)) {
        return null;
      }
      W3CTracestate tracestate;
      try {
        // merged into a higher-priority context, so only what the merge reads is checked
        tracestate = W3CTracestate.parseForLowerPriorityMerge(tracestateHeader);
      } catch (RuntimeException e) {
        log.debug("Exception when building context", e);
        return null;
      }
      int overridingPriority =
          overridesTracestatePriority(samplingPriority, tracestate.getSamplingPriority())
              ? samplingPriority
              : PrioritySampling.UNSET;
      return new TraceContextMerge(traceIdHigh, traceIdLow, spanId, tracestate, overridingPriority);
    }

    void parseTraceStateHeader(String tracestate) {
      if (tracestate == null || tracestate.isEmpty()) {
        this.propagationTags = this.propagationTagsFactory.empty();
      } else {
        this.propagationTags = this.propagationTagsFactory.fromHeaderValue(W3C, tracestate);
      }
      this.lastParentId = this.propagationTags.getLastParentId();
      int ptagsPriority = this.propagationTags.getSamplingPriority();
      int contextPriority = this.samplingPriority;
      if (overridesTracestatePriority(contextPriority, ptagsPriority)) {
        // Override Datadog sampling priority with W3C one
        this.propagationTags.updateTraceSamplingPriority(
            contextPriority, SamplingMechanism.EXTERNAL_OVERRIDE);
      } else {
        // Use more detailed Datadog sampling priority in context
        this.samplingPriority = ptagsPriority;
      }
      // Use the origin
      this.origin = this.propagationTags.getOrigin();
      // Ensure TraceId high-order bits match
      this.propagationTags.updateTraceIdHighOrderBits(this.traceId.toHighOrderLong());
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
