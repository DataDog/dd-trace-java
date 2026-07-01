package datadog.trace.instrumentation.java.lang.jdk22;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class NativeLibraryInstrumentation
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice,
        Instrumenter.ForBootstrap {
  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("jdk.internal.loader.NativeLibrary"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), "datadog.trace.instrumentation.java.lang.jdk22.CaptureLibraryNameAdvice");
    transformer.applyAdvice(
        isMethod().and(named("find")).and(returns(long.class)),
        "datadog.trace.instrumentation.java.lang.jdk22.CaptureSymbolAddressAdvice");
  }
}
