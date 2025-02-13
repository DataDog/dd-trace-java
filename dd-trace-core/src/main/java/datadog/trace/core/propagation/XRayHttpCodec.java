package datadog.trace.core.propagation;

import static datadog.trace.api.DDTags.ORIGIN_KEY;
import static datadog.trace.api.TracePropagationStyle.XRAY;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A codec designed for AWS requests using the {@code X-Amzn-Trace-Id} tracing header.
 * https://docs.aws.amazon.com/xray/latest/devguide/xray-concepts.html#xray-concepts-tracingheader
 */
class XRayHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(XRayHttpCodec.class);

  static final String X_AMZN_TRACE_ID = "X-Amzn-Trace-Id";

  static final String ROOT = "Root";
  static final String PARENT = "Parent";
  static final String SAMPLED = "Sampled";
  static final String SELF = "Self";

  static final String ROOT_PREFIX = ROOT + "=1-";
  static final String TRACE_ID_PADDING = "-00000000";
  static final String PARENT_PREFIX = PARENT + '=';
  static final String SAMPLED_PREFIX = SAMPLED + '=';
  static final String SELF_PREFIX = SELF + '=';
  static final String ORIGIN_PREFIX = ORIGIN_KEY + '=';

  static final int ROOT_PREAMBLE = ROOT_PREFIX.length() + 8; // prefix plus 8-character epoch

  static final String E2E_START_KEY = DDTags.TRACE_START_TIME;
  static final String E2E_START_PREFIX = E2E_START_KEY + '=';

  static final int MAX_ADDITIONAL_BYTES = 256;

  private XRayHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  public static HttpCodec.Injector newInjector(Map<String, String> invertedBaggageMapping) {
    return new Injector(invertedBaggageMapping);
  }

  private static class Injector implements HttpCodec.Injector {

    private final Map<String, String> invertedBaggageMapping;

    public Injector(Map<String, String> invertedBaggageMapping) {
      this.invertedBaggageMapping = invertedBaggageMapping;
    }

    @Override
    public <C> void inject(DDSpanContext context, C carrier, CarrierSetter<C> setter) {
      long e2eStart = context.getEndToEndStartTime();

      StringBuilder buf =
          new StringBuilder()
              .append(ROOT_PREFIX)
              .append(
                  String.format(
                      "%08x",
                      e2eStart > 0
                          ? NANOSECONDS.toSeconds(e2eStart)
                          : MILLISECONDS.toSeconds(
                              context.getTraceCollector().getTimeSource().getCurrentTimeMillis())))
              .append(TRACE_ID_PADDING)
              .append(context.getTraceId().toHexStringPadded(16))
              .append(';' + PARENT_PREFIX)
              .append(DDSpanId.toHexStringPadded(context.getSpanId()));

      if (context.lockSamplingPriority()) {
        buf.append(';' + SAMPLED_PREFIX)
            .append(convertSamplingPriority(context.getSamplingPriority()));
      }

      int maxCapacity = buf.length() + MAX_ADDITIONAL_BYTES;

      CharSequence origin = context.getOrigin();
      if (origin != null) {
        additionalPart(buf, ORIGIN_KEY, origin.toString(), maxCapacity);
      }
      if (e2eStart > 0) {
        additionalPart(
            buf, E2E_START_KEY, Long.toString(NANOSECONDS.toMillis(e2eStart)), maxCapacity);
      }

      for (Map.Entry<String, String> entry : context.baggageItems()) {
        String header = invertedBaggageMapping.getOrDefault(entry.getKey(), entry.getKey());
        if (!isReserved(header)) {
          additionalPart(buf, header, HttpCodec.encode(entry.getValue()), maxCapacity);
        }
      }

      setter.set(carrier, X_AMZN_TRACE_ID, buf.toString());
    }

    private boolean isReserved(String key) {
      return ROOT.equals(key) || PARENT.equals(key) || SAMPLED.equals(key) || SELF.equals(key);
    }

    private char convertSamplingPriority(int samplingPriority) {
      return samplingPriority > 0 ? '1' : '0';
    }

    private void additionalPart(StringBuilder buf, String key, String value, int maxCapacity) {
      if (buf.length() + key.length() + value.length() + 2 <= maxCapacity) {
        buf.append(';').append(key).append('=').append(value);
      }
    }
  }

  public static HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(traceConfigSupplier, () -> new XRayContextInterpreter(config));
  }

  static class XRayContextInterpreter extends ContextInterpreter {

    private XRayContextInterpreter(Config config) {
      super(config);
    }

    @Override
    public TracePropagationStyle style() {
      return XRAY;
    }

    @Override
    public boolean accept(String key, String value) {
      if (null == key || key.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }
      try {
        char first = Character.toLowerCase(key.charAt(0));
        switch (first) {
          case 'x':
            if (X_AMZN_TRACE_ID.equalsIgnoreCase(key)) {
              handleXRayTraceHeader(this, value);
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
        } else {
          handleTags(key, value);
        }

        if (!baggageMapping.isEmpty()) {
          String mappedKey = baggageMapping.get(toLowerCase(key));
          if (null != mappedKey) {
            addBaggageItem(this, mappedKey, HttpCodec.decode(value));
          }
        }
        return true;
      } catch (RuntimeException e) {
        invalidateContext();
        log.debug("Exception when extracting context", e);
        return false;
      }
    }

    static void handleXRayTraceHeader(ContextInterpreter interpreter, String value) {
      if (null != value) {
        int rootPart = value.indexOf(ROOT_PREFIX);
        if (rootPart < 0
            || !value.regionMatches(
                rootPart + ROOT_PREAMBLE, TRACE_ID_PADDING, 0, TRACE_ID_PADDING.length())) {
          return; // header doesn't match our padded version, ignore it
        }
        int startPart = 0;
        int length = value.length();
        while (startPart < length) {
          int endPart = value.indexOf(';', startPart);
          if (endPart < 0) {
            endPart = length;
          }
          String part = value.substring(startPart, endPart).trim();
          if (part.startsWith(ROOT_PREFIX)) {
            if (interpreter.traceId == null || interpreter.traceId == DDTraceId.ZERO) {
              interpreter.traceId =
                  DD64bTraceId.fromHex(part.substring(ROOT_PREAMBLE + TRACE_ID_PADDING.length()));
            }
          } else if (part.startsWith(PARENT_PREFIX)) {
            if (interpreter.spanId == DDSpanId.ZERO) {
              interpreter.spanId = DDSpanId.fromHex(part.substring(PARENT_PREFIX.length()));
            }
          } else if (part.startsWith(SAMPLED_PREFIX)) {
            if (interpreter.samplingPriority == PrioritySampling.UNSET) {
              interpreter.samplingPriority =
                  convertSamplingPriority(part.charAt(SAMPLED_PREFIX.length()));
            }
          } else if (part.startsWith(SELF_PREFIX)) {
            // Self is added by load-balancers and should be ignored
          } else if (part.startsWith(ORIGIN_PREFIX)) {
            interpreter.origin = part.substring(ORIGIN_PREFIX.length());
          } else if (part.startsWith(E2E_START_PREFIX)) {
            interpreter.endToEndStartTime =
                extractEndToEndStartTime(part.substring(E2E_START_PREFIX.length()));
          } else {
            int eqIndex = part.indexOf('=');
            if (eqIndex > 0) {
              addBaggageItem(interpreter, part.substring(0, eqIndex), part.substring(eqIndex + 1));
            }
          }
          startPart = endPart + 1;
        }
      }
    }

    private static long extractEndToEndStartTime(String value) {
      try {
        return MILLISECONDS.toNanos(Long.parseLong(value));
      } catch (RuntimeException e) {
        log.debug("Ignoring invalid end-to-end start time {}", value, e);
        return 0;
      }
    }

    private static int convertSamplingPriority(char samplingPriority) {
      return '1' == samplingPriority ? SAMPLER_KEEP : SAMPLER_DROP;
    }

    private static void addBaggageItem(ContextInterpreter interpreter, String key, String value) {
      if (interpreter.baggage.isEmpty()) {
        interpreter.baggage = new TreeMap<>();
      }
      interpreter.baggage.put(key, HttpCodec.decode(value));
    }
  }
}
