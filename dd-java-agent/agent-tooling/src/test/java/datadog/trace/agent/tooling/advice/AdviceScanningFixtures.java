package datadog.trace.agent.tooling.advice;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.advice.AdviceScanningHelper.Dependency;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.instrumentation.testing.AdviceHierarchy;
import datadog.trace.instrumentation.testing.ExternalHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.bytebuddy.jar.asm.ClassReader;

final class AdviceScanningFixtures {
  private AdviceScanningFixtures() {}

  static class AdviceSuperclass {
    AdviceSuperclass(String value) {}
  }

  static class AdviceRoot extends AdviceSuperclass {
    AdviceRoot() {
      super("advice");
    }

    static String apply(String value) {
      Dependency.field = value;
      Dependency dependency = new Dependency();
      Dependency[] array = new Dependency[1];
      Dependency[][] matrix = new Dependency[1][1];
      Class<?> type = Dependency.class;
      Supplier<Dependency> constructor = Dependency::new;
      List<String> library = new ArrayList<>();
      Class<?> externalLibrary = ClassReader.class;
      return dependency.method(
          array.length
              + matrix.length
              + type.getName()
              + constructor.get()
              + library
              + externalLibrary
              + AdviceScanningHelper.localClass()
              + ExternalHelper.typeName());
    }
  }

  static class AdditionalAdvice {
    static void apply() {
      new Dependency();
    }
  }

  static class CatchAdvice {
    static void apply() {
      CatchOnlyHelper.run();
    }
  }

  static class HierarchyAdvice extends AdviceHierarchy.Superclass
      implements AdviceHierarchy.Interface {
    static void apply() {}
  }

  public static final class HierarchyModule extends ScanModule {
    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(null, HierarchyAdvice.class.getName());
    }
  }

  public static final class CatchModule extends ScanModule {
    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(null, CatchAdvice.class.getName());
    }
  }

  public static final class NestedHelperModule extends ScanModule {
    @Override
    public void methodAdvice(MethodTransformer transformer) {
      transformer.applyAdvice(null, NestedAdvice.class.getName());
    }

    static class NestedAdvice {
      static String apply() {
        return Helper.run();
      }
    }

    public static class Helper {
      public static String run() {
        return "nested helper";
      }
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
