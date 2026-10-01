package datadog.trace.instrumentation.spray;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.SPRAY_HTTP_SERVER;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.asm.Advice;
import spray.http.HttpRequest;
import spray.routing.RequestContext;

public class SprayHttpServerRunSealedRouteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope enter(
            @Advice.Argument(value = 1, readOnly = false) RequestContext ctx,
            @Advice.Local("completion") AtomicInteger completion) {
        final Context parentContext;
        final Context context;
        final AgentSpan span;
        if (activeSpan() == null) {
            // Propagate context in case income request was going through several routes
            // TODO: Add test for it
            final HttpRequest request = ctx.request();
            parentContext = DECORATE.extract(request);
            context = DECORATE.startSpan(request, parentContext);
            span = spanFromContext(context);
        } else {
            parentContext = rootContext();
            span = startSpan(SPRAY_HTTP_SERVER.toString(), DECORATE.spanName());
            context = span;
        }

        ContextScope scope = context.attach();
        DECORATE.afterStart(span);

        completion = new AtomicInteger();
        ctx = SprayHelper.wrapRequestContext(ctx, span, parentContext, context, completion);
        return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
            @Advice.Enter final ContextScope scope,
            @Advice.Thrown final Throwable throwable,
            @Advice.Local("completion") AtomicInteger completion) {
        try {
            if (throwable != null) {
                DECORATE.onError(scope, throwable);
            }
        } finally {
            scope.close();
            SprayHelper.scopeClosed(spanFromContext(scope.context()), completion);
        }
    }
}
