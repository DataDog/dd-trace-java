package datadog.trace.agent.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.Sets;
import datadog.trace.agent.tooling.bytebuddy.matcher.GlobalIgnores;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import net.bytebuddy.utility.nullability.MaybeNull;

public class ClassFileTransformerListener implements AgentBuilder.Listener {

  final Set<String> transformedClassesNames = Sets.newConcurrentHashSet();
  final Set<TypeDescription> transformedClassesTypes = Sets.newConcurrentHashSet();
  final AtomicInteger instrumentationErrorCount = new AtomicInteger(0);

  @Override
  public void onTransformation(
      TypeDescription typeDescription,
      @MaybeNull ClassLoader classLoader,
      @MaybeNull JavaModule module,
      boolean loaded,
      DynamicType dynamicType) {
    this.transformedClassesNames.add(typeDescription.getActualName());
    this.transformedClassesTypes.add(typeDescription);
  }

  @SuppressForbidden // Allows System.out.println
  @Override
  public void onError(
      String typeName,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      Throwable throwable) {
    // Incorrect* classes assert on incorrect api usage. Error expected.
    if (typeName.startsWith("context.FieldInjectionTestInstrumentation$Incorrect")
        && throwable.getMessage().startsWith("Incorrect Context Api Usage detected.")) {
      return;
    }

    System.out.println(
        "Unexpected instrumentation error when instrumenting " + typeName + " on " + classLoader);
    throwable.printStackTrace();
    instrumentationErrorCount.incrementAndGet();
  }

  @Override
  public void onDiscovery(
      String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
    // Nothing special to do
  }

  @Override
  public void onIgnored(
      TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, boolean loaded) {
    // Nothing special to do
  }

  @Override
  public void onComplete(
      String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
    // Nothing special to do
  }

  public void verify() {
    // Check instrumentation errors
    int errorCount = this.instrumentationErrorCount.get();
    assertEquals(0, errorCount, errorCount + " instrumentation errors during test");
    // Check effectively transformed classes that should have been ignored
    assertTrue(
        this.transformedClassesTypes.stream()
            .map(TypeDescription::getActualName)
            .noneMatch(GlobalIgnores::isAdditionallyIgnored),
        "Transformed classes match global libraries ignore matcher");
  }
}
