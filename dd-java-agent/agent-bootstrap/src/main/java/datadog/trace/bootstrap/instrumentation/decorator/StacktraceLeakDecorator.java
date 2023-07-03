package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

import static datadog.trace.api.gateway.Events.EVENTS;

public class StacktraceLeakDecorator {

    public static final StacktraceLeakDecorator DECORATE = new StacktraceLeakDecorator();

    public void onStacktraceLeak(final AgentSpan span, Throwable throwable, boolean leaked) {
        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        RequestContext requestContext = span.getRequestContext();
        if (cbp == null || requestContext == null) {
            return;
        }
        TriFunction<RequestContext, Throwable, Boolean, Flow<Void>> addrCallback =
                cbp.getCallback(EVENTS.responseStacktrace());
        if (null != addrCallback) {
            addrCallback.apply(requestContext, throwable, leaked);
        }
    }
}
