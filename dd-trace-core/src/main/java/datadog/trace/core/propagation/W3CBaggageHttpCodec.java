package datadog.trace.core.propagation;

import static datadog.trace.api.TracePropagationStyle.BAGGAGE;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.core.propagation.HttpCodec.firstHeaderValue;
import static datadog.trace.core.propagation.PropagationTags.HeaderType.W3C;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A codec designed for HTTP transport via headers using W3C "baggage" header */
class W3CBaggageHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(W3CHttpCodec.class);

  static final String BAGGAGE_KEY = "baggage";

  private W3CBaggageHttpCodec() {
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
      StringBuilder baggageHeader = new StringBuilder();

      for (final Map.Entry<String, String> entry : context.baggageItems()) {
        if (baggageHeader.length() > 0) {
            baggageHeader.append(",");
        }

        String header = invertedBaggageMapping.get(entry.getKey());
        header = header != null ? header : entry.getKey();
        String value = HttpCodec.encodeBaggage(entry.getValue());

        baggageHeader.append(header).append("=").append(value);
      }

      setter.set(carrier, BAGGAGE_KEY, baggageHeader.toString());
    }
  }

  public static HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return new TagContextExtractor(traceConfigSupplier, () -> new W3CBaggageContextInterpreter(config));
  }

  private static class W3CBaggageContextInterpreter extends ContextInterpreter {

    private W3CBaggageContextInterpreter(Config config) {
      super(config);
    }

    @Override
    public TracePropagationStyle style() {
      return BAGGAGE;
    }

    @Override
    public boolean accept(String key, String value) {
      if (null == key || key.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }

      char first = Character.toLowerCase(key.charAt(0));

      if (first == 'b' && BAGGAGE_KEY.equalsIgnoreCase(key)) {
        try {
          if (baggage.isEmpty()) {
            baggage = new TreeMap<>();
          }

          for (String entry : value.split(",")) {
            String[] keyValue = entry.split("=", 2);
            if (keyValue.length == 2) {
                baggage.put(
                    HttpCodec.decode(keyValue[0].trim()),
                    HttpCodec.decode(keyValue[1].trim())
                );
            }
          }
        } catch (RuntimeException e) {
          invalidateContext();
          log.debug("Exception when extracting baggage", e);
          return false;
        }
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
