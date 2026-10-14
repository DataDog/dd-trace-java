package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.ResourceMethodSpanState;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import javax.ws.rs.container.AsyncResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class JaxRsAsyncResponseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JaxRsAsyncResponseInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-annotations");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "javax.ws.rs.container.AsyncResponse", ResourceMethodSpanState.class.getName());
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.ws.rs.container.AsyncResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JaxRsAnnotationsDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("resume")
            .and(takesArguments(1))
            .and(takesArgument(0, Object.class))
            .and(returns(boolean.class))
            .and(isPublic()),
        JaxRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseAdvice");
    transformer.applyAdvice(
        named("resume")
            .and(takesArguments(1))
            .and(takesArgument(0, Throwable.class))
            .and(returns(boolean.class))
            .and(isPublic()),
        JaxRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseThrowableAdvice");
    transformer.applyAdvice(
        named("cancel").and(returns(boolean.class)),
        JaxRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseCancelAdvice");
  }

  public static class AsyncResponseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ResourceMethodSpanState beginTerminal(
        @Advice.This final AsyncResponse asyncResponse) {
      return DECORATE.beginTerminal(
          InstrumentationContext.get(AsyncResponse.class, ResourceMethodSpanState.class),
          asyncResponse);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Enter final ResourceMethodSpanState state,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      final ContextStore<AsyncResponse, ResourceMethodSpanState> contextStore =
          InstrumentationContext.get(AsyncResponse.class, ResourceMethodSpanState.class);
      try {
        if (throwable != null || succeeded) {
          final AgentSpan span = DECORATE.claimTerminalOutcome(state);
          if (span != null) {
            DECORATE.onError(span, throwable);
          }
        }
      } finally {
        DECORATE.endTerminal(contextStore, asyncResponse, state);
      }
    }
  }

  public static class AsyncResponseThrowableAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ResourceMethodSpanState beginTerminal(
        @Advice.This final AsyncResponse asyncResponse) {
      return DECORATE.beginTerminal(
          InstrumentationContext.get(AsyncResponse.class, ResourceMethodSpanState.class),
          asyncResponse);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Enter final ResourceMethodSpanState state,
        @Advice.Argument(0) final Throwable throwable,
        @Advice.Thrown final Throwable methodThrew,
        @Advice.Return final boolean succeeded) {
      final ContextStore<AsyncResponse, ResourceMethodSpanState> contextStore =
          InstrumentationContext.get(AsyncResponse.class, ResourceMethodSpanState.class);
      try {
        if (methodThrew != null || succeeded) {
          final AgentSpan span = DECORATE.claimTerminalOutcome(state);
          if (span != null) {
            DECORATE.onError(span, throwable);
          }
        }
      } finally {
        DECORATE.endTerminal(contextStore, asyncResponse, state);
      }
    }
  }

  public static class AsyncResponseCancelAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ResourceMethodSpanState beginTerminal(
        @Advice.This final AsyncResponse asyncResponse) {
      return DECORATE.beginTerminal(
          InstrumentationContext.get(AsyncResponse.class, ResourceMethodSpanState.class),
          asyncResponse);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Enter final ResourceMethodSpanState state,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      final ContextStore<AsyncResponse, ResourceMethodSpanState> contextStore =
          InstrumentationContext.get(AsyncResponse.class, ResourceMethodSpanState.class);
      try {
        if (throwable != null || succeeded) {
          final AgentSpan span = DECORATE.claimTerminalOutcome(state);
          if (span != null) {
            if (throwable != null) {
              DECORATE.onError(span, throwable);
            } else {
              span.setTag("canceled", true);
            }
          }
        }
      } finally {
        DECORATE.endTerminal(contextStore, asyncResponse, state);
      }
    }
  }
}
