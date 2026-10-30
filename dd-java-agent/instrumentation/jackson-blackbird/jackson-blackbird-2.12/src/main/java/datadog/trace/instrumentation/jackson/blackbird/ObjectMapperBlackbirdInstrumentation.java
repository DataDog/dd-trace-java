package datadog.trace.instrumentation.jackson.blackbird;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class ObjectMapperBlackbirdInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ObjectMapperBlackbirdInstrumentation() {
    super("jackson-blackbird");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && InstrumenterConfig.get().isJacksonBlackbirdEnabled()
        && JavaVirtualMachine.isJavaVersionAtLeast(11);
  }

  @Override
  public String instrumentedType() {
    return "com.fasterxml.jackson.databind.ObjectMapper";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("com.fasterxml.jackson.module.blackbird.BlackbirdModule");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor()
            .and(takesArguments(3))
            .and(takesArgument(0, named("com.fasterxml.jackson.core.JsonFactory"))),
        getClass().getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This final ObjectMapper mapper) {
      BlackbirdRegistrar.onConstructed(mapper);
    }
  }
}
