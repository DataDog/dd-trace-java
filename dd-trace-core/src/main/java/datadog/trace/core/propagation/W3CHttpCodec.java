package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * A Codec designed for HTTP Context propagation using W3C Trace Context Propagation
 *
 */

public class W3CHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(W3CHttpCodec.class);

  static final String TRACE_PARENT = "traceparent";
  static final String TRACE_STATE = "tracestate";
  static final String VERSION = "00";


  private W3CHttpCodec() {
    // This class should not be created. This also makes code coverage checks happy.
  }

  private static class Injector implements HttpCodec.Injector {

    @Override
    public <C> void inject(
        final DDSpanContext context, final C carrier, final AgentPropagation.Setter<C> setter) {
      //add logic to determine if traceid is valid or if it needs to be updated

      //verify that injectedTraceID is padded according to rfc
      final String injectedTraceId = context.getTraceId().toHexStringPadded(32);
      final String injectedSpanId = context.getSpanId().toHexString();

      final StringBuilder injectedTraceparent = new StringBuilder(64);

      //W3C traceparent format: version - trace-id - parent-id - trace_flags
      injectedTraceparent.append(VERSION).append('-').append(injectedTraceId).append('-')
          .append(injectedSpanId).append('-').append("00");
      setter.set(carrier, TRACE_PARENT, injectedTraceparent.toString());

      final StringBuilder injectedTracestate = new StringBuilder();
      injectedTracestate.append("dd.context").append(contextList(context));
    }
  }

  public static HttpCodec.Extractor newExtractor(final Map<String, String> tagMapping) {
    return new TagContextExtractor(
        tagMapping,
        new ContextInterpreter.Factory() {
          @Override
          protected ContextInterpreter construct(final Map<String, String> mapping) {
            return new B3HttpCodec.W3CContextInterpreter(mapping);
          }
        });
  }

  private static String contextList(final DDSpanContext context) {
    return "";
  }

  private static class W3CContextInterpreter extends ContextInterpreter {


    private W3CContextInterpreter(Map<String, String> taggedHeaders) {
      super(taggedHeaders);
    }

    @Override
    public boolean accept(String key, String value) {
      if (null == key || key.isEmpty()) {
        return true;
      }
      if (LOG_EXTRACT_HEADER_NAMES) {
        log.debug("Header: {}", key);
      }
      return false;
    }

  }
}
