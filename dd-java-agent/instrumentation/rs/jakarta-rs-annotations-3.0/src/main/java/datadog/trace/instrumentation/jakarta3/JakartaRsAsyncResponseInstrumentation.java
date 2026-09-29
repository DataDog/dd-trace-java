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
import datadog.trace.bootstrap.ContextStore;
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

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      if (throwable == null && !succeeded) {
        // AsyncResponse#resume() returns false when the response was already resolved (a
        // second resume()/cancel() call on the same response); nothing changed, so there is
        // nothing to tag or finish. If the call threw instead of returning, treat it the same
        // as before: @Advice.Return defaults to false on the exception path, so that alone
        // does not mean "already resolved".
        return;
      }

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        DECORATE.onError(span, throwable);
        // If this resource method's invocation is still open -- however deeply other
        // instrumented calls have nested in the meantime -- let its own exit advice close the
        // scope and finish the span instead of finishing it here, which would both
        // double-finish the span and finish it prematurely while the resource method may
        // still be doing work under it.
        DECORATE.finishUnlessOpenOrAlreadyClaimed(contextStore, asyncResponse, span);
      }
    }
  }

  public static class AsyncResponseThrowableAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Argument(0) final Throwable throwable,
        @Advice.Thrown final Throwable methodThrew,
        @Advice.Return final boolean succeeded) {
      if (methodThrew == null && !succeeded) {
        // see comment in AsyncResponseAdvice#stopSpan
        return;
      }

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        DECORATE.onError(span, throwable);
        // see comment in AsyncResponseAdvice#stopSpan
        DECORATE.finishUnlessOpenOrAlreadyClaimed(contextStore, asyncResponse, span);
      }
    }
  }

  public static class AsyncResponseCancelAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This final AsyncResponse asyncResponse,
        @Advice.Thrown Throwable throwable,
        @Advice.Return final boolean succeeded) {
      if (throwable == null && !succeeded) {
        // see comment in AsyncResponseAdvice#stopSpan
        return;
      }

      final ContextStore<AsyncResponse, AgentSpan> contextStore =
          InstrumentationContext.get(AsyncResponse.class, AgentSpan.class);

      final AgentSpan span = contextStore.get(asyncResponse);
      if (span != null) {
        if (throwable != null) {
          DECORATE.onError(span, throwable);
        } else {
          span.setTag("canceled", true);
        }
        // see comment in AsyncResponseAdvice#stopSpan
        DECORATE.finishUnlessOpenOrAlreadyClaimed(contextStore, asyncResponse, span);
      }
    }
  }
}
