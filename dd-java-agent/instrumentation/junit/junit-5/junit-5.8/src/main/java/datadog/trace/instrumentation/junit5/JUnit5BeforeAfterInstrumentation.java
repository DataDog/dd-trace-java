package datadog.trace.instrumentation.junit5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.engine.extension.ExtensionRegistrar;

@AutoService(InstrumenterModule.class)
public class JUnit5BeforeAfterInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JUnit5BeforeAfterInstrumentation() {
    super("ci-visibility", "junit-5", "setup-teardown");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.junit.jupiter.engine.extension.ExtensionRegistrar";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".BeforeAfterOperationsTracer",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        JUnit5BeforeAfterInstrumentation.class.getName() + "$RegisterExtensionAdvice");
  }

  public static class RegisterExtensionAdvice {
    @Advice.OnMethodExit
    public static void registerExtension(@Advice.This ExtensionRegistrar extensionRegistrar) {
      extensionRegistrar.registerExtension(BeforeAfterOperationsTracer.class);
    }
  }
}
