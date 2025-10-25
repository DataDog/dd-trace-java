package datadog.trace.instrumentation.vertx_4_0.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPackagePrivate;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty41.AttributeKeys;
import io.vertx.core.http.impl.HttpClientStream;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class HttpClientRequestBaseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  static final String[] CONCRETE_TYPES = {
    "io.vertx.core.http.impl.HttpClientRequestBase",
    "io.vertx.core.http.impl.HttpClientRequestImpl",
    "io.vertx.core.http.impl.HttpClientRequestPushPromise"
  };

  public HttpClientRequestBaseInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.instrumentation.netty41.AttributeKeys"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPackagePrivate().or(isPrivate()))
            .and(named("reset"))
            .and(takesArgument(0, named("java.lang.Throwable"))),
        HttpClientRequestBaseInstrumentation.class.getName() + "$ResetAdvice");
  }

  @Override
  public String[] knownMatchingTypes() {
    return CONCRETE_TYPES;
  }

  public static class ResetAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(value = 0) Throwable cause,
        @Advice.FieldValue("stream") final HttpClientStream stream,
        @Advice.Return boolean result) {
      if (result) {
        Context storedContext =
            stream.connection().channel().attr(AttributeKeys.CONTEXT_ATTRIBUTE_KEY).get();
        AgentSpan nettySpan = spanFromContext(storedContext);
        if (nettySpan != null) {
          try (final AgentScope scope = activateSpan(nettySpan)) {
            DECORATE.onError(scope, cause);
          }
        }
      }
    }
  }
}
