/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package datadog.trace.instrumentation.log4j27;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.apache.logging.log4j.core.ContextDataInjector;

@AutoService(InstrumenterModule.class)
public class ContextDataInjectorFactoryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public ContextDataInjectorFactoryInstrumentation() {
    super("log4j", "log4j-2");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.logging.log4j.core.impl.ContextDataInjectorFactory";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".SpanDecoratingContextDataInjector"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(isStatic())
            .and(named("createInjector"))
            .and(returns(named("org.apache.logging.log4j.core.ContextDataInjector"))),
        ContextDataInjectorFactoryInstrumentation.class.getName() + "$CreateInjectorAdvice");
  }

  public static class CreateInjectorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false)
            ContextDataInjector injector) {
      injector = new SpanDecoratingContextDataInjector(injector);
    }
  }
}
