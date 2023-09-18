package test

import datadog.appsec.api.blocking.BlockingContentType
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.EventType
import datadog.trace.api.gateway.Events
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.instrumentation.servlet.ServletBlockingHelper
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

class SetupSpecHelper {
  static void provideBlockResponseFunction() {

    EventType<TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>> uriEvent = Events.get().requestMethodUriRaw()

    // get original callback
    CallbackProvider provider = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC)
    TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>> origUriCallback = provider.getCallback(uriEvent)

    // wrap the original IG callbacks
    def ss = AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC)
    ss.reset(uriEvent)
    ss.registerCallback(uriEvent, new TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>() {
        @Override
        Flow<Void> apply(RequestContext requestContext, String s, URIDataAdapter uriDataAdapter) {
          requestContext.setBlockResponseFunction(TestSpringBlockResponseFunction.INSTANCE)
          origUriCallback.apply(requestContext, s, uriDataAdapter)
        }
      })
  }

  enum TestSpringBlockResponseFunction implements BlockResponseFunction {
    INSTANCE

    @Override
    boolean tryCommitBlockingResponse(TraceSegment segment, int statusCode, BlockingContentType templateType, Map<String, String> extraHeaders) {
      ServletRequestAttributes attributes = RequestContextHolder.requestAttributes
      if (attributes) {
        ServletBlockingHelper
          .commitBlockingResponse(segment, attributes.request, attributes.response, statusCode, templateType, extraHeaders)
      }
      true
    }
  }
}
