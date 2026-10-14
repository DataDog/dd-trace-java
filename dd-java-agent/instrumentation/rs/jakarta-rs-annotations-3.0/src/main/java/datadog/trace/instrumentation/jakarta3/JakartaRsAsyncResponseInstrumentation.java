package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.ws.rs.container.AsyncResponse;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class JakartaRsAsyncResponseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JakartaRsAsyncResponseInstrumentation() {
    super("jakarta-rs", "jakartars", "jakarta-rs-annotations");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "jakarta.ws.rs.container.AsyncResponse", AgentSpan.class.getName());
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.ws.rs.container.AsyncResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JakartaRsAnnotationsDecorator",
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
        JakartaRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseAdvice");
    transformer.applyAdvice(
        named("resume")
            .and(takesArguments(1))
            .and(takesArgument(0, Throwable.class))
            .and(returns(boolean.class))
            .and(isPublic()),
        JakartaRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseThrowableAdvice");
    transformer.applyAdvice(
        named("cancel").and(returns(boolean.class)),
        JakartaRsAsyncResponseInstrumentation.class.getName() + "$AsyncResponseCancelAdvice");
  }

  public static class AsyncResponseAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan claimSpan(@Advice.This final AsyncResponse asyncResponse) {
      return DECORATE.claimUnlessOpen(
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class), asyncResponse);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Enter final AgentSpan claimed,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      // AsyncResponse#resume() returns false when the response was already resolved (a second
      // resume()/cancel() call on the same response); nothing changed, so there is nothing to
      // tag. If the call threw instead of returning, treat it the same as before:
      // @Advice.Return defaults to false on the exception path, so that alone does not mean
      // "already resolved".
      if (throwable != null || succeeded) {
        final AgentSpan span =
            DECORATE.terminalSpan(
                InstrumentationContext.get(AsyncResponse.class, AgentSpan.class),
                asyncResponse,
                claimed);
        if (span != null) {
          DECORATE.onError(span, throwable);
        }
      }
      // Tagging above happens before this finish, so a racing resource-method exit can never
      // publish the span without it: that exit only finishes spans it claimed itself.
      DECORATE.finishClaimed(claimed);
    }
  }

  public static class AsyncResponseThrowableAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan claimSpan(@Advice.This final AsyncResponse asyncResponse) {
      return DECORATE.claimUnlessOpen(
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class), asyncResponse);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Enter final AgentSpan claimed,
        @Advice.Argument(0) final Throwable throwable,
        @Advice.Thrown final Throwable methodThrew,
        @Advice.Return final boolean succeeded) {
      // see comments in AsyncResponseAdvice#stopSpan
      if (methodThrew != null || succeeded) {
        final AgentSpan span =
            DECORATE.terminalSpan(
                InstrumentationContext.get(AsyncResponse.class, AgentSpan.class),
                asyncResponse,
                claimed);
        if (span != null) {
          DECORATE.onError(span, throwable);
        }
      }
      DECORATE.finishClaimed(claimed);
    }
  }

  public static class AsyncResponseCancelAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan claimSpan(@Advice.This final AsyncResponse asyncResponse) {
      return DECORATE.claimUnlessOpen(
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class), asyncResponse);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Enter final AgentSpan claimed,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      // see comments in AsyncResponseAdvice#stopSpan
      if (throwable != null || succeeded) {
        final AgentSpan span =
            DECORATE.terminalSpan(
                InstrumentationContext.get(AsyncResponse.class, AgentSpan.class),
                asyncResponse,
                claimed);
        if (span != null) {
          if (throwable != null) {
            DECORATE.onError(span, throwable);
          } else {
            span.setTag("canceled", true);
          }
        }
      }
      DECORATE.finishClaimed(claimed);
    }
  }
}
