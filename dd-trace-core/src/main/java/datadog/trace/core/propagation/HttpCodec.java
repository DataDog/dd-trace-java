package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpCodec {
  public interface Injector {
    <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter);
  }

  public interface Extractor {
    <C> TagContext extract(final C carrier, final AgentPropagation.ContextVisitor<C> getter);
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
      switch (style) {
        case DATADOG:
          extractors.add(DatadogHttpCodec.newExtractor(taggedHeaders));
          break;
        case HAYSTACK:
          extractors.add(HaystackHttpCodec.newExtractor(taggedHeaders));
          break;
        case B3:
          extractors.add(B3HttpCodec.newExtractor(taggedHeaders));
          break;
        default:
          log.debug("No implementation found to extract propagation style: {}", style);
      }
    }
    return new CompoundExtractor(extractors);
  }

  public static class CompoundInjector implements Injector {

    private final List<Injector> injectors;

    public CompoundInjector(final List<Injector> injectors) {
      this.injectors = injectors;
    }

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      for (final Injector injector : injectors) {
        injector.inject(context, carrier, setter);
      }
    }
  }

  public static class CompoundExtractor implements Extractor {

    private final List<Extractor> extractors;

    public CompoundExtractor(final List<Extractor> extractors) {
      this.extractors = extractors;
    }

    @Override
    public <C> TagContext extract(
        final C carrier, final AgentPropagation.ContextVisitor<C> setter) {
      TagContext context = null;
      for (final Extractor extractor : extractors) {
        context = extractor.extract(carrier, setter);
        // Use incomplete TagContext only as last resort
        if (context instanceof ExtractedContext) {
          return context;
        }
      }
      return context;
    }
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

  static String firstHeaderValue(final String value) {
    if (value == null) {
      return null;
    }

    int firstComma = value.indexOf(',');
    return firstComma == -1 ? value : value.substring(0, firstComma).trim();
  }
}
