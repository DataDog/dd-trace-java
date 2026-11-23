package datadog.trace.instrumentation.guidewire;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Propagates trace context across the raw thread Guidewire's WSI layer spawns per outbound SOAP
 * call ({@code AsyncResponseImpl$WebserviceInvocationThread}, seen at runtime as {@code
 * "WSI-Invocation"}).
 *
 * <p>{@code java.lang.Thread} can't be instrumented (agent global-ignore + bootstrap), so we match
 * the application-loaded worker subclass instead: capture the context in its {@code <init>} (still
 * on the parent thread) and re-activate it in {@code run()}. Both halves live here so it works even
 * if the runnable/executor integration is off; if that also wraps {@code run()}, the double
 * activation is safe because the continuation is consumed once.
 *
 * <p>Known limitation: the context is captured in {@code <init>} and only released when {@code
 * run()} executes. If a worker is constructed under an active span but never started or run (a rare
 * caller error path), its continuation is not released and the enclosing trace stays pending until
 * the tracer's timeout drops it — other traces are unaffected. Releasing it would need a "will not
 * run" hook, which a raw thread does not expose, or coupling to Guidewire's closed-source,
 * version-specific {@code AsyncResponseImpl} internals; so {@code <init>} remains the only reliable
 * capture point.
 */
@AutoService(InstrumenterModule.class)
public final class WsiAsyncResponseInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String ASYNC_RESPONSE = "gw.internal.xml.ws.AsyncResponseImpl";

  public WsiAsyncResponseInstrumentation() {
    super("guidewire");
  }

  @Override
  public String hierarchyMarkerType() {
    return ASYNC_RESPONSE;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // '$' matches only nested classes of AsyncResponseImpl, not top-level siblings.
    return nameStartsWith(ASYNC_RESPONSE + "$").and(extendsClass(named("java.lang.Thread")));
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Capture");
    transformer.applyAdvice(
        named("run").and(takesArguments(0)).and(isPublic()), getClass().getName() + "$Activate");
  }

  public static final class Capture {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onConstruct(@Advice.This final Runnable thiz) {
      capture(InstrumentationContext.get(Runnable.class, State.class), thiz);
    }
  }

  public static final class Activate {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope enter(@Advice.This final Runnable thiz) {
      return startTaskScope(InstrumentationContext.get(Runnable.class, State.class), thiz);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final ContextScope scope) {
      endTaskScope(scope);
    }
  }
}
