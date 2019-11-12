package datadog.opentracing.propagation;

import datadog.opentracing.DDSpanContext;
import datadog.opentracing.DDTracer;
import datadog.trace.api.Config;
import io.opentracing.SpanContext;
import io.opentracing.propagation.TextMapExtract;
import io.opentracing.propagation.TextMapInject;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpCodec {
  public interface Injector {

    void inject(final DDSpanContext context, final TextMapInject carrier);
  }

  public interface Extractor {

    SpanContext extract(final TextMapExtract carrier);
  }

  public static Injector createInjector(final Config config) {
    final List<Injector> injectors = new ArrayList<>();
    for (final Config.PropagationStyle style : config.getPropagationStylesToInject()) {
      if (style == Config.PropagationStyle.DATADOG) {
        injectors.add(new DatadogHttpCodec.Injector());
        continue;
      }
      if (style == Config.PropagationStyle.B3) {
        injectors.add(new B3HttpCodec.Injector());
        continue;
      }
      if (style == Config.PropagationStyle.HAYSTACK) {
        injectors.add(new HaystackHttpCodec.Injector());
        continue;
      }
      log.debug("No implementation found to inject propagation style: {}", style);
    }
    return new CompoundInjector(injectors);
  }

  public static Extractor createExtractor(
      final Config config, final Map<String, String> taggedHeaders) {
    final List<Extractor> extractors = new ArrayList<>();
    for (final Config.PropagationStyle style : config.getPropagationStylesToExtract()) {
      if (style == Config.PropagationStyle.DATADOG) {
        extractors.add(new DatadogHttpCodec.Extractor(taggedHeaders));
        continue;
      }
      if (style == Config.PropagationStyle.B3) {
        extractors.add(new B3HttpCodec.Extractor(taggedHeaders));
        continue;
      }
      if (style == Config.PropagationStyle.HAYSTACK) {
        extractors.add(new HaystackHttpCodec.Extractor(taggedHeaders));
        continue;
      }
      log.debug("No implementation found to extract propagation style: {}", style);
    }
    return new CompoundExtractor(extractors);
  }

  public static class CompoundInjector implements Injector {

    private final List<Injector> injectors;

    public CompoundInjector(final List<Injector> injectors) {
      this.injectors = injectors;
    }

    @Override
    public void inject(final DDSpanContext context, final TextMapInject carrier) {
      for (final Injector injector : injectors) {
        injector.inject(context, carrier);
      }
    }
  }

  public static class CompoundExtractor implements Extractor {

    private final List<Extractor> extractors;

    public CompoundExtractor(final List<Extractor> extractors) {
      this.extractors = extractors;
    }

    @Override
    public SpanContext extract(final TextMapExtract carrier) {
      SpanContext context = null;
      for (final Extractor extractor : extractors) {
        context = extractor.extract(carrier);
        // Use incomplete TagContext only as last resort
        if (context != null && (context instanceof ExtractedContext)) {
          return context;
        }
      }
      return context;
    }
  }

  /**
   * Helper method to validate an ID String to verify within range
   *
   * @param value the String that contains the ID
   * @param radix radix to use to parse the ID
   * @return the parsed ID
   * @throws IllegalArgumentException if value cannot be converted to integer or doesn't conform to
   *     required boundaries
   */
  static BigInteger validateUInt64BitsID(final String value, final int radix)
      throws IllegalArgumentException {
    final BigInteger parsedValue = new BigInteger(value, radix);
    if (parsedValue.compareTo(DDTracer.TRACE_ID_MIN) < 0
        || parsedValue.compareTo(DDTracer.TRACE_ID_MAX) > 0) {
      throw new IllegalArgumentException(
          "ID out of range, must be between 0 and 2^64-1, got: " + value);
    }

    return parsedValue;
  }

  /** URL encode value */
  static String encode(final String value) {
    String encoded = value;
    try {
      encoded = URLEncoder.encode(value, "UTF-8");
    } catch (final UnsupportedEncodingException e) {
      log.info("Failed to encode value - {}", value);
    }
    return encoded;
  }

  /** URL decode value */
  static String decode(final String value) {
    String decoded = value;
    try {
      decoded = URLDecoder.decode(value, "UTF-8");
    } catch (final UnsupportedEncodingException e) {
      log.info("Failed to decode value - {}", value);
    }
    return decoded;
  }
}
