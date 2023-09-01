package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.SPAN_CONTEXT_KEY;
import static java.util.Collections.unmodifiableSet;

import datadog.trace.api.Config;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.scopemanager.ScopeContext;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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

  // other headers which may contain real ip
  static final String X_CLIENT_IP_KEY = "x-client-ip";
  static final String TRUE_CLIENT_IP_KEY = "true-client-ip";
  static final String X_CLUSTER_CLIENT_IP_KEY = "x-cluster-client-ip";
  static final String X_REAL_IP_KEY = "x-real-ip";
  static final String USER_AGENT_KEY = "user-agent";
  static final String FASTLY_CLIENT_IP_KEY = "fastly-client-ip";
  static final String CF_CONNECTING_IP_KEY = "cf-connecting-ip";
  static final String CF_CONNECTING_IP_V6_KEY = "cf-connecting-ipv6";

  public interface Injector {
    <C> void inject(
        final AgentScopeContext context, final C carrier, final AgentPropagation.Setter<C> setter);

    /**
     * Gets the span context to inject.
     *
     * @param context The context to inject.
     * @return The span context to inject if existing, with a sampling decision, {@code null} if no
     *     span context.
     */
    default DDSpanContext getSpanContextToInject(AgentScopeContext context) {
      AgentSpan agentSpan = context.get(SPAN_CONTEXT_KEY);
      if (agentSpan != null && agentSpan.context() instanceof DDSpanContext) {
        DDSpanContext spanContext = (DDSpanContext) agentSpan.context();
        spanContext.getTrace().setSamplingPriorityIfNecessary();
        return spanContext;
      }
      return null;
    }
  }

  public interface Extractor {
    <C> void extract(
        final ScopeContextBuilder builder,
        final C carrier,
        final AgentPropagation.ContextVisitor<C> getter);

    Set<ContextKey<?>> supportedContent();
  }

  public interface ScopeContextBuilder {
    <T> void append(ContextKey<T> key, T value);

    ScopeContext build();
  }

  public abstract static class AbstractScopeContextBuilder implements ScopeContextBuilder {
    private final Map<ContextKey<?>, Object> content;

    protected AbstractScopeContextBuilder() {
      this.content = new HashMap<>();
    }

    @Override
    public <T> void append(ContextKey<T> key, T value) {
      Object originalValue = this.content.get(key);
      if (originalValue != null) {
        //noinspection unchecked
        this.content.put(key, merge(key, (T) originalValue, value));
      } else {
        this.content.put(key, value);
      }
    }

    protected abstract <T> T merge(ContextKey<T> key, T originalValue, T newValue);

    @Override
    public ScopeContext build() {
      ScopeContext context = ScopeContext.fromMap(this.content);
      this.content.clear();
      return context;
    }
  }

  public static class ScopeContextAppender extends AbstractScopeContextBuilder {
    @Override
    protected <T> T merge(ContextKey<T> key, T originalValue, T newValue) {
      return newValue;
    }
  }

  public static class ScopeContextMerger extends AbstractScopeContextBuilder {
    @Override
    protected <T> T merge(ContextKey<T> key, T originalValue, T newValue) {
      /*
       * Handle span context case:
       * - Prioritize full span context (ExtractedContext) over partial (TagContext)
       * - Keep original full span context rather than new to keep extractor priority
       * - Move DSM pathway context to any updated span context
       */
      if (originalValue instanceof TagContext && newValue instanceof TagContext) {
        TagContext originalTagContext = (TagContext) originalValue;
        TagContext newTagContext = (TagContext) newValue;
        // Prioritize full span context
        if (!(originalValue instanceof ExtractedContext) && newValue instanceof ExtractedContext) {
          PathwayContext originalPathwayContext = originalTagContext.getPathwayContext();
          if (newTagContext.getPathwayContext() == null) {
            // Restore DSM pathway context if present
            newTagContext.withPathwayContext(originalPathwayContext);
          }
          return newValue;
        } else if (newTagContext.getPathwayContext() != null) {
          // Apply DSM pathway context to original span context
          originalTagContext.withPathwayContext(newTagContext.getPathwayContext());
        }
      }
      return originalValue;
    }
  }

  public static Injector createInjector(
      Config config,
      Set<TracePropagationStyle> styles,
      Map<String, String> invertedBaggageMapping) {
    ArrayList<Injector> injectors =
        new ArrayList<>(createInjectors(config, styles, invertedBaggageMapping).values());
    return new CompoundInjector(injectors);
  }

  public static Map<TracePropagationStyle, Injector> allInjectorsFor(
      Config config, Map<String, String> reverseBaggageMapping) {
    return createInjectors(
        config, EnumSet.allOf(TracePropagationStyle.class), reverseBaggageMapping);
  }

  private static Map<TracePropagationStyle, Injector> createInjectors(
      Config config,
      Set<TracePropagationStyle> propagationStyles,
      Map<String, String> reverseBaggageMapping) {
    EnumMap<TracePropagationStyle, Injector> result = new EnumMap<>(TracePropagationStyle.class);
    for (TracePropagationStyle style : propagationStyles) {
      switch (style) {
        case DATADOG:
          result.put(style, DatadogHttpCodec.newInjector(reverseBaggageMapping));
          break;
        case B3SINGLE:
          result.put(
              style,
              B3HttpCodec.newSingleInjector(config.isTracePropagationStyleB3PaddingEnabled()));
          break;
        case B3MULTI:
          result.put(
              style,
              B3HttpCodec.newMultiInjector(config.isTracePropagationStyleB3PaddingEnabled()));
          break;
        case HAYSTACK:
          result.put(style, HaystackHttpCodec.newInjector(reverseBaggageMapping));
          break;
        case XRAY:
          result.put(style, XRayHttpCodec.newInjector(reverseBaggageMapping));
          break;
        case NONE:
          result.put(style, NoneCodec.INJECTOR);
          break;
        case TRACECONTEXT:
          result.put(style, W3CHttpCodec.newInjector(reverseBaggageMapping));
          break;
        case DSM_PATHWAY_CONTEXT:
          // Skip DSM injector as DSM is needed for creation
          break;
        default:
          log.debug("No implementation found to inject propagation style: {}", style);
          break;
      }
    }
    return result;
  }

  public static Extractor createExtractor(
      Config config,
      Supplier<TraceConfig> traceConfigSupplier,
      List<Extractor> additionalExtractors) {
    final List<Extractor> extractors = new ArrayList<>();
    for (final TracePropagationStyle style : config.getTracePropagationStylesToExtract()) {
      switch (style) {
        case DATADOG:
          extractors.add(DatadogHttpCodec.newExtractor(config, traceConfigSupplier));
          break;
        case B3SINGLE:
          extractors.add(B3HttpCodec.newSingleExtractor(config, traceConfigSupplier));
          break;
        case B3MULTI:
          extractors.add(B3HttpCodec.newMultiExtractor(config, traceConfigSupplier));
          break;
        case HAYSTACK:
          extractors.add(HaystackHttpCodec.newExtractor(config, traceConfigSupplier));
          break;
        case XRAY:
          extractors.add(XRayHttpCodec.newExtractor(config, traceConfigSupplier));
          break;
        case NONE:
          extractors.add(NoneCodec.EXTRACTOR);
          break;
        case TRACECONTEXT:
          extractors.add(W3CHttpCodec.newExtractor(config, traceConfigSupplier));
          break;
        default:
          log.debug("No implementation found to extract propagation style: {}", style);
          break;
      }
    }
    extractors.addAll(additionalExtractors);
    return new CompoundExtractor(extractors);
  }

  public static class CompoundInjector implements Injector {

    private final List<Injector> injectors;

    public CompoundInjector(final List<Injector> injectors) {
      this.injectors = injectors;
    }

    @Override
    public <C> void inject(
        final AgentScopeContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      log.debug("Inject context {}", context);
      for (final Injector injector : injectors) {
        injector.inject(context, carrier, setter);
      }
    }
  }

  public static class CompoundExtractor implements Extractor {
    private final List<Extractor> extractors;
    private final Set<ContextKey<?>> supportedContent;

    public CompoundExtractor(final List<Extractor> extractors) {
      this.extractors = extractors;
      Set<ContextKey<?>> mergedSupportedContent = new HashSet<>();
      for (Extractor extractor : this.extractors) {
        mergedSupportedContent.addAll(extractor.supportedContent());
      }
      this.supportedContent = unmodifiableSet(mergedSupportedContent);
    }

    @Override
    public Set<ContextKey<?>> supportedContent() {
      return this.supportedContent;
    }

    @Override
    public <C> void extract(
        final ScopeContextBuilder builder,
        final C carrier,
        final AgentPropagation.ContextVisitor<C> getter) {
      boolean spanContextComplete = false;

      // TODO IMPLEMENT FAST STOP
      // Stop trying to extract if all supported content
      // were already extracted.
      // Special case for SCOPE_CONTEXT that can accept
      // multiple TagContext until first ExtractedContext

      for (final Extractor extractor : this.extractors) {
        extractor.extract(builder, carrier, getter);
      }
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
    } catch (final UnsupportedEncodingException | IllegalArgumentException e) {
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
