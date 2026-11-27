package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.dispatch.Mailbox;
import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/** Propagates context through the Runnable wrapped by Akka's fork-join task. */
@AutoService(InstrumenterModule.class)
public final class AkkaForkJoinExecutorTaskInstrumentation
    extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType,
        Instrumenter.ForConfiguredType,
        Instrumenter.HasMethodAdvice {
  public AkkaForkJoinExecutorTaskInstrumentation() {
    super("java_concurrent", "akka_concurrent");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "akka.dispatch.ForkJoinExecutorConfigurator$AkkaForkJoinTask";
  }

  @Override
  public String configuredMatchingType() {
    return InstrumenterConfig.get().getAkkaForkJoinExecutorTaskName();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$Construct");
    transformer.applyAdvice(
        isMethod()
            .and(named("run"))
            .and(takesArgument(0, hasInterface(named(Runnable.class.getName())))),
        getClass().getName() + "$RunWithArgument");
    // Akka 2.11 invokes the wrapped Runnable directly from both run() and exec().
    transformer.applyAdvice(
        isMethod()
            .and(namedOneOf("run", "exec"))
            .and(takesArguments(0))
            .and(
                isDeclaredBy(
                    declaresMethod(named("run").and(takesArguments(0)))
                        .and(declaresField(named("runnable").and(fieldType(Runnable.class)))))),
        getClass().getName() + "$RunWithField");
  }

  public static final class Construct {
    @Advice.OnMethodExit
    public static void construct(@Advice.Argument(0) Runnable wrapped, @Advice.This Object task) {
      // Akka 2.11 wraps mailbox scheduling. Message envelopes already carry context.
      if (!(task instanceof Runnable && wrapped instanceof Mailbox)) {
        capture(InstrumentationContext.get(Runnable.class, State.class), wrapped);
      }
    }
  }

  public static final class RunWithArgument {
    @Advice.OnMethodEnter
    public static ContextScope before(@Advice.Argument(0) Runnable wrapped) {
      return startTaskScope(InstrumentationContext.get(Runnable.class, State.class), wrapped);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void after(@Advice.Enter ContextScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class RunWithField {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope before(@Advice.FieldValue("runnable") Runnable wrapped) {
      return startTaskScope(InstrumentationContext.get(Runnable.class, State.class), wrapped);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter ContextScope scope) {
      endTaskScope(scope);
    }
  }
}
