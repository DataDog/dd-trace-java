// package datadog.trace.instrumentation.elasticsearch7_3;
//
// import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
// import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
// import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
// import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
// import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
// import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
// import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
// import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
// import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
// import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
// import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;
//
// import com.google.auto.service.AutoService;
// import datadog.trace.agent.tooling.Instrumenter;
// import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
// import datadog.trace.bootstrap.InstrumentationContext;
// import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
// import datadog.trace.context.TraceScope;
// import net.bytebuddy.asm.Advice;
// import net.bytebuddy.description.type.TypeDescription;
// import net.bytebuddy.matcher.ElementMatcher;
// import org.elasticsearch.action.ActionRunnable;
//
// import java.util.Collections;
// import java.util.Map;
//
// @AutoService(Instrumenter.class)
// public class ActionRunnableInstrumentation extends Instrumenter.Tracing {
//
//  public ActionRunnableInstrumentation() {
//    super("elasticsearch", "elasticsearch-transport", "elasticsearch-transport-7");
//  }
//
//  @Override
//  public ElementMatcher<? super TypeDescription> typeMatcher() {
//    return NameMatchers.<TypeDescription>nameStartsWith("org.elasticsearch")
//      .and(declaresMethod(named("doRun")))
//      .and(extendsClass(named("org.elasticsearch.action.ActionRunnable")));
//  }
//
//  @Override
//  public Map<String, String> contextStore() {
//    return Collections.singletonMap("org.elasticsearch.action.ActionRunnable",
// State.class.getName());
//  }
//
//  @Override
//  public void adviceTransformations(AdviceTransformation transformation) {
//    transformation.applyAdvice(named("doRun").and(takesNoArguments()), getClass().getName() +
// "$Run");
//
// transformation.applyAdvice(isConstructor().and(isDeclaredBy(named("org.elasticsearch.action.ActionRunnable")))
//      .and(takesArguments(1)), getClass().getName() + "$Construct");
//  }
//
//  @SuppressWarnings("rawtypes")
//  public static final class Construct {
//    @Advice.OnMethodExit
//    public static void construct(@Advice.This ActionRunnable task) {
//      capture(InstrumentationContext.get(ActionRunnable.class, State.class), task, true);
//    }
//  }
//
//  @SuppressWarnings("rawtypes")
//  public static final class Run {
//    @Advice.OnMethodEnter
//    public static TraceScope before(@Advice.This ActionRunnable task) {
//      return startTaskScope(InstrumentationContext.get(ActionRunnable.class, State.class), task);
//    }
//
//    @Advice.OnMethodExit(onThrowable = Throwable.class)
//    public static void after(@Advice.Enter TraceScope scope) {
//      endTaskScope(scope);
//    }
//  }
// }
