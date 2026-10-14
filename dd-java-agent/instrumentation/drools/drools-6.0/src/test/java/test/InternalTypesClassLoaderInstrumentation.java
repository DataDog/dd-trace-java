package test;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class InternalTypesClassLoaderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final String INTERNAL_TYPES_CLASSLOADER_NAME =
      "org.drools.wiring.dynamic.DynamicProjectClassLoader$DefaultInternalTypesClassLoader";

  public InternalTypesClassLoaderInstrumentation() {
    super("drools-test");
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return new ElementMatcher.Junction.ForNonNullValues<ClassLoader>() {
      @Override
      protected boolean doMatch(ClassLoader loader) {
        return INTERNAL_TYPES_CLASSLOADER_NAME.equals(loader.getClass().getName());
      }
    };
  }

  @Override
  public String instrumentedType() {
    return "example.GeneratedFact";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), "test.ConstructorAdvice");
  }
}
