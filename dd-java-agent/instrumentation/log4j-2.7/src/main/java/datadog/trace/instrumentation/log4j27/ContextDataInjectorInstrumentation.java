/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package datadog.trace.instrumentation.log4j27;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.logging.log4j.core.ContextDataInjector;
import org.apache.logging.log4j.util.StringMap;

@AutoService(Instrumenter.class)
public class ContextDataInjectorInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public ContextDataInjectorInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.logging.log4j.core.ContextDataInjector";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".DebugHelper"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    String clzName = ContextDataInjectorInstrumentation.class.getName();
    transformation.applyAdvice(isConstructor(), clzName + "$CreateInjectorAdvice");
    transformation.applyAdvice(
        isMethod().and(named("injectContextData")).and(takesArguments(2)),
        clzName + "$InjectContextDataAdvice");
  }

  public static class CreateInjectorAdvice {
    @Advice.OnMethodExit()
    public static void onExit(@Advice.This ContextDataInjector zis) {
      DebugHelper.debugLogWithException("injectorConstructor {}", zis);
    }
  }

  public static class InjectContextDataAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.This(typing = Assigner.Typing.DYNAMIC) ContextDataInjector zis,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) StringMap contextData,
        @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        DebugHelper.debugLog("injectorInjectContextData", throwable);
        return;
      }
      AgentSpan span = activeSpan();
      DebugHelper.debugLog(
          "injectorInjectContextData {} {} - contextData={}", zis, span, contextData);
    }
  }
}
