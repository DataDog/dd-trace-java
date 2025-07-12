package datadog.trace.core.apigw;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.bootstrap.instrumentation.api.InferredProxyContext;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InferredProxyPropagator implements Propagator {
  private static final Logger log = LoggerFactory.getLogger(InferredProxyPropagator.class);
  static final String INFERRED_PROXY_KEY = "x-dd-proxy";
  static final String REQUEST_TIME_KEY = "x-dd-proxy-request-time-ms";
  static final String DOMAIN_NAME_KEY = "x-dd-proxy-domain-name";
  static final String HTTP_METHOD_KEY = "x-dd-proxy-httpmethod";
  static final String PATH_KEY = "x-dd-proxy-path";
  static final String STAGE_KEY = "x-dd-proxy-stage";

  // Supported proxies mapping (header value -> canonical component name)
  static final Map<String, String> SUPPORTED_PROXIES;

  static {
    SUPPORTED_PROXIES = new HashMap<>();
    SUPPORTED_PROXIES.put("aws-apigateway", "aws.apigateway");
  }

  /**
   * METHOD STUB: InferredProxy is currently not meant to be injected to downstream services
   *
   * @param context the context containing the values to be injected.
   * @param carrier the instance that will receive the key/value pairs to propagate.
   * @param setter the callback to set key/value pairs into the carrier.
   */
  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {}

  /**
   * Extracts an InferredProxyContext from un upstream service and stores it as part of the Context
   * object
   *
   * @param context the base context to store the extracted values on top, use {@link
   *     Context#root()} for a default base context.
   * @param carrier the instance to fetch the propagated key/value pairs from.
   * @param visitor the callback to walk over the carrier and extract its key/value pais.
   * @return A context with the extracted values on top of the given base context.
   */
  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    if (context == null || carrier == null || visitor == null) {
      return context;
    }
    InferredProxyContextExtractor extractor = new InferredProxyContextExtractor();
    visitor.forEachKeyValue(carrier, extractor);

    InferredProxyContext extractedContext = extractor.extractedContext;
    // Mandatory headers for APIGW
    if (extractedContext == null || !extractedContext.validContext()) {
      return context;
    }
    return context.with(extractedContext);
  }

  public static class InferredProxyContextExtractor implements BiConsumer<String, String> {
    private InferredProxyContext extractedContext;

    InferredProxyContextExtractor() {}

    /**
     * Performs this operation on the given arguments.
     *
     * @param key the first input argument from an http header
     * @param value the second input argument from an http header
     */
    @Override
    public void accept(String key, String value) {
      if (key == null || key.isEmpty() || !key.startsWith(INFERRED_PROXY_KEY)) {
        return;
      }

      if (extractedContext == null) {
        extractedContext = new InferredProxyContext();
      }

      switch (key) {
        case INFERRED_PROXY_KEY:
          if (SUPPORTED_PROXIES.containsKey(value)) {
            extractedContext.setProxyName(SUPPORTED_PROXIES.get(value));
            extractedContext.setComponentName(value);
          }
          break;
        case REQUEST_TIME_KEY:
          extractedContext.setStartTime(value);
          break;
        case DOMAIN_NAME_KEY:
          extractedContext.setDomainName(value);
          break;
        case HTTP_METHOD_KEY:
          extractedContext.setHttpMethod(value);
          break;
        case PATH_KEY:
          extractedContext.setPath(value);
          break;
        case STAGE_KEY:
          extractedContext.setStage(value);
          break;
        default:
          log.info(
              "Extracting Inferred Proxy header that doesn't match accepted Inferred Proxy keys");
      }
    }
  }
}
