package datadog.trace.agent.tooling.advice;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.bytebuddy.jar.asm.ClassReader;

final class AdviceScanningFixtures {
  private AdviceScanningFixtures() {}

  static final class Dependency {
    static String field;

    Dependency() {}

    String method(String value) {
      return value;
    }
  }

  static class AdviceRoot {
    static String apply(String value) {
      Dependency.field = value;
      Dependency dependency = new Dependency();
      Dependency[] array = new Dependency[1];
      Class<?> type = Dependency.class;
      Supplier<Dependency> constructor = Dependency::new;
      List<String> library = new ArrayList<>();
      Class<?> externalLibrary = ClassReader.class;
      return dependency.method(
          array.length + type.getName() + constructor.get() + library + externalLibrary);
    }
  }

  static class AdditionalAdvice {
    static void apply() {
      new Dependency();
    }
  }

  public static class ScanModule extends InstrumenterModule
      implements Instrumenter.HasMethodAdvice {
    static int adviceRegistrations;

    public ScanModule() {
      super("advice-scan-test");
    }

    @Override
    public void methodAdvice(MethodTransformer transformer) {
      adviceRegistrations++;
      transformer.applyAdvices(null, AdviceRoot.class.getName(), AdditionalAdvice.class.getName());
    }
  }

  public static final class PipelineModule extends ScanModule {
    static int instances;

    public PipelineModule() {
      instances++;
    }

    @Override
    public String[] muzzleIgnoredClassNames() {
      return new String[] {ClassReader.class.getName()};
    }

    @Override
    public Reference[] additionalMuzzleReferences() {
      return new Reference[] {new Reference.Builder("extra/AddedReference").build()};
    }
  }
}
