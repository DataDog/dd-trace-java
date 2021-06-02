package datadog.trace.core.propagation;

import datadog.trace.api.Config;
import datadog.trace.api.Function;
import datadog.trace.api.PropagationStyle;
import datadog.trace.api.function.Supplier;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Events;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
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
  static final String FORWARDED_PROTO_KEY = "x-forwarded-proto";
  static final String FORWARDED_HOST_KEY = "x-forwarded-host";
  static final String FORWARDED_FOR_KEY = "x-forwarded-for";
  static final String FORWARDED_PORT_KEY = "x-forwarded-port";

  public interface Injector {
    <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter);
  }

  public interface Extractor {
    <C> TagContext extract(final C carrier, final AgentPropagation.ContextVisitor<C> getter);
  }

  public static Injector createInjector(final Config config) {
    final List<Injector> injectors = new ArrayList<>();
    for (final PropagationStyle style : config.getPropagationStylesToInject()) {
      if (style == PropagationStyle.DATADOG) {
        injectors.add(new DatadogHttpCodec.Injector());
        continue;
      }
      if (style == PropagationStyle.B3) {
        injectors.add(new B3HttpCodec.Injector());
        continue;
      }
      if (style == PropagationStyle.HAYSTACK) {
        injectors.add(new HaystackHttpCodec.Injector());
        continue;
      }
      log.debug("No implementation found to inject propagation style: {}", style);
    }
    return new CompoundInjector(injectors);
  }

  public static Extractor createExtractor(
      final Config config,
      final Map<String, String> taggedHeaders,
      CallbackProvider callbackProvider) {
    final List<Extractor> extractors = new ArrayList<>();
    for (final PropagationStyle style : config.getPropagationStylesToExtract()) {
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
    return new CompoundExtractor(extractors, callbackProvider);
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
    private final CallbackProvider callbackProvider;

    public CompoundExtractor(final List<Extractor> extractors, CallbackProvider callbackProvider) {
      this.extractors = extractors;
      this.callbackProvider = callbackProvider;
    }

    @Override
    public <C> TagContext extract(
        final C carrier, final AgentPropagation.ContextVisitor<C> getter) {
      TagContext context = null;
      RequestContext requestContext = null;

      if (null != callbackProvider) {
        Supplier<Flow<RequestContext>> startedCB =
            callbackProvider.getCallback(Events.REQUEST_STARTED);
        if (null != startedCB) {
          requestContext = startedCB.get().getResult();
          IGKeyClassifier igKeyClassifier =
              IGKeyClassifier.create(
                  requestContext,
                  callbackProvider.getCallback(Events.REQUEST_HEADER),
                  callbackProvider.getCallback(Events.REQUEST_HEADER_DONE));
          if (null != igKeyClassifier) {
            getter.forEachKey(carrier, igKeyClassifier);
            igKeyClassifier.done();
          }
        }
      }

      for (final Extractor extractor : extractors) {
        context = extractor.extract(carrier, getter);
        // Use incomplete TagContext only as last resort
        if (context instanceof ExtractedContext) {
          return context.withRequestContext(requestContext);
        }
      }

      if (null != requestContext) {
        if (context == null) {
          context = TagContext.empty();
        }
        return context.withRequestContext(requestContext);
      } else {
        return context;
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

  private static final class IGKeyClassifier implements AgentPropagation.KeyClassifier {

    private static IGKeyClassifier create(
        RequestContext requestContext,
        TriConsumer<RequestContext, String, String> headerCallback,
        Function<RequestContext, Flow<Void>> doneCallback) {
      if (null == requestContext || null == headerCallback) {
        return null;
      }
      return new IGKeyClassifier(requestContext, headerCallback, doneCallback);
    }

    private final RequestContext requestContext;
    private final TriConsumer<RequestContext, String, String> headerCallback;
    private final Function<RequestContext, Flow<Void>> doneCallback;

    private IGKeyClassifier(
        RequestContext requestContext,
        TriConsumer<RequestContext, String, String> headerCallback,
        Function<RequestContext, Flow<Void>> doneCallback) {
      this.requestContext = requestContext;
      this.headerCallback = headerCallback;
      this.doneCallback = doneCallback;
    }

    @Override
    public boolean accept(String key, String value) {
      headerCallback.accept(requestContext, key, value);
      return true;
    }

    public void done() {
      if (null != doneCallback) {
        doneCallback.apply(requestContext);
      }
    }
  }
}
