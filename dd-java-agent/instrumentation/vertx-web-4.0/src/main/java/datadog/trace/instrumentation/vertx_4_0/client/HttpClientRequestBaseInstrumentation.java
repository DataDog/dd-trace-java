package datadog.trace.instrumentation.vertx_4_0.client;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlConnectionDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPackagePrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty41.AttributeKeys;
import io.vertx.core.http.impl.HttpClientStream;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class HttpClientRequestBaseInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {
  static final String[] CONCRETE_TYPES = {
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPackagePrivate())
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
        @Advice.Return(readOnly = false) boolean result) {
      if (result) {
        AgentSpan nettySpan =
            stream.connection().channel().attr(AttributeKeys.SPAN_ATTRIBUTE_KEY).get();
        if (nettySpan != null) {
          try (final AgentScope scope = activateSpan(nettySpan)) {
            DECORATE.onError(scope, cause);
          }
        }
      }
    }
  }
}
