package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.api.PropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpCodec {

  private static final Logger log = LoggerFactory.getLogger(HttpCodec.class);
  // https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Forwarded
  static final String FORWARDED_KEY = "forwarded";
  static final String FORWARDED_FOR_KEY = "forwarded-for";
  static final String X_FORWARDED_PROTO_KEY = "x-forwarded-proto";
  static final String X_FORWARDED_HOST_KEY = "x-forwarded-host";
  static final String X_FORWARDED_KEY = "x-forwarded";
  static final String X_FORWARDED_FOR_KEY = "x-forwarded-for";
  static final String X_FORWARDED_PORT_KEY = "x-forwarded-port";

  // Headers which may contain real ip
  static final String CLIENT_IP_KEY = "client-ip";
  static final String TRUE_CLIENT_IP_KEY = "true-client-ip";
  static final String X_CLUSTER_CLIENT_IP_KEY = "x-cluster-client-ip";
  static final String X_REAL_IP_KEY = "x-real-ip";
  static final String USER_AGENT_KEY = "user-agent";
  static final String VIA_KEY = "via";

  public interface Injector {
    <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter);
  }

  public interface Extractor {
    <C> TagContext extract(final C carrier, final AgentPropagation.ContextVisitor<C> getter);
  }

  public static <C> void inject(
      DDSpanContext context, C carrier, AgentPropagation.Setter<C> setter, PropagationStyle style) {
    switch (style) {
      case DATADOG:
        DatadogHttpCodec.INJECTOR.inject(context, carrier, setter);
        break;
      case B3:
        B3HttpCodec.INJECTOR.inject(context, carrier, setter);
        break;
      case HAYSTACK:
        HaystackHttpCodec.INJECTOR.inject(context, carrier, setter);
        break;
      case XRAY:
        XRayHttpCodec.INJECTOR.inject(context, carrier, setter);
        break;
      default:
        log.debug("No implementation found to inject propagation style: {}", style);
        break;
    }
  }

  public static Injector createInjector(final Config config) {
    final List<Injector> injectors = new ArrayList<>();
    for (final PropagationStyle style : config.getPropagationStylesToInject()) {
      switch (style) {
        case DATADOG:
          injectors.add(DatadogHttpCodec.INJECTOR);
          break;
        case B3:
          injectors.add(B3HttpCodec.INJECTOR);
          break;
        case HAYSTACK:
          injectors.add(HaystackHttpCodec.INJECTOR);
          break;
        case XRAY:
          injectors.add(XRayHttpCodec.INJECTOR);
          break;
        default:
          log.debug("No implementation found to inject propagation style: {}", style);
          break;
      }
    }
    return new CompoundInjector(injectors);
  }

  public static Extractor createExtractor(
      final Config config, final Map<String, String> taggedHeaders) {
    final List<Extractor> extractors = new ArrayList<>();
    for (final PropagationStyle style : config.getPropagationStylesToExtract()) {
      switch (style) {
        case DATADOG:
          extractors.add(DatadogHttpCodec.newExtractor(taggedHeaders));
          break;
        case B3:
          extractors.add(B3HttpCodec.newExtractor(taggedHeaders));
          break;
        case HAYSTACK:
          extractors.add(HaystackHttpCodec.newExtractor(taggedHeaders));
          break;
        case XRAY:
          extractors.add(XRayHttpCodec.newExtractor(taggedHeaders));
          break;
        default:
          log.debug("No implementation found to extract propagation style: {}", style);
          break;
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
        final C carrier, final AgentPropagation.ContextVisitor<C> getter) {
      TagContext context = null;

      for (final Extractor extractor : extractors) {
        context = extractor.extract(carrier, getter);
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
      log.debug("Failed to encode value - {}", value);
    }
    return encoded;
  }

  /** URL decode value */
  static String decode(final String value) {
    String decoded = value;
    try {
      decoded = URLDecoder.decode(value, "UTF-8");
    } catch (final UnsupportedEncodingException e) {
      log.debug("Failed to decode value - {}", value);
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
