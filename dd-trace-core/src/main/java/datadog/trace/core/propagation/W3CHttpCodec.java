package datadog.trace.core.propagation;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.DDSpanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Codec designed for HTTP Context propagation using W3C Trace Context Propagation
 *
 */

public class W3CHttpCodec {
  private static final Logger log = LoggerFactory.getLogger(W3CHttpCodec.class);

  static final String TRACE_PARENT = "traceparent";
  static final String TRACE_STATE = "tracestate";
  static final String VERSION = "00";


  private W3CHttpCodec(){
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
      setter.set(carrier,TRACE_PARENT,injectedTraceparent.toString());

      final StringBuilder injectedTracestate = new StringBuilder();

    }
  }


}
