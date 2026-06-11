package datadog.trace.instrumentation.junit5.execution;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.Config;
import net.bytebuddy.asm.ModifierAdjustment;
import net.bytebuddy.description.modifier.FieldManifestation;

/**
 * Strips the {@code final} modifier from {@code AbstractTestDescriptor#uniqueId} when the class is
 * loaded. {@link TestDescriptorHandle} overwrites this field on cloned descriptors to give each
 * test retry a distinct unique ID, and mutating final fields via reflection or method handles is
 * forbidden by <a href="https://openjdk.org/jeps/500">JEP 500</a>. Mutating non-final fields
 * remains legal, and since the modifier is stripped before any instance of the class is created,
 * the JVM never relies on the field being final.
 */
@AutoService(InstrumenterModule.class)
public class JUnit5TestDescriptorInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasTypeAdvice {

  public JUnit5TestDescriptorInstrumentation() {
    super("ci-visibility", "junit-5", "test-retry");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isCiVisibilityExecutionPoliciesEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.junit.platform.engine.support.descriptor.AbstractTestDescriptor";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return TestDescriptorHandle.MuzzleHelper.compileReferences().toArray(new Reference[0]);
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(
        new ModifierAdjustment()
            .withFieldModifiers(NameMatchers.named("uniqueId"), FieldManifestation.PLAIN));
  }
}
