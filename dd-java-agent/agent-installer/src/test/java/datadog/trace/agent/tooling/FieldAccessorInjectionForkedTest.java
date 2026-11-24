package datadog.trace.agent.tooling;

import static net.bytebuddy.matcher.ElementMatchers.none;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.instrument.classinject.ClassInjector;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.memoize.MemoizedMatchers;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.EnumSet;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import org.junit.jupiter.api.Test;

/**
 * An accessor interface that isn't on the bootstrap class path is injected into the instrumented
 * class's loader by {@code installFieldAccessors}, with nothing declared: the target class here is
 * defined by a loader that can't see the test's classes, so it can only implement the interface if
 * it was injected.
 */
class FieldAccessorInjectionForkedTest {

  private static final String TARGET_CLASS_NAME =
      "datadog.trace.agent.tooling.FieldAccessorInjectionForkedTest$Target";

  /** The accessor interface, a class of the "module" rather than of agent-bootstrap. */
  public interface TargetFields {
    String $$DD$$__name();
  }

  /**
   * The library class; never referenced as a class literal, so the test's loader never loads it.
   */
  public static class Target {
    private final String name;

    public Target(String name) {
      this.name = name;
    }
  }

  @Test
  void injectsTheAccessorInterfaceIntoTheInstrumentedClassesLoader() throws Exception {
    Instrumentation instrumentation = ByteBuddyAgent.install();
    ClassInjector.enableClassInjection(instrumentation); // as the agent does at startup
    ClassFileTransformer transformer = installTransformer(instrumentation, new AccessorModule());
    try {
      // a loader with only the bootstrap loader as parent: TargetFields isn't visible to it
      ClassLoader isolated =
          new ByteArrayClassLoader(
              null, Collections.singletonMap(TARGET_CLASS_NAME, classBytes(TARGET_CLASS_NAME)));
      Class<?> target = isolated.loadClass(TARGET_CLASS_NAME);

      assertEquals(1, target.getInterfaces().length);
      Class<?> injected = target.getInterfaces()[0];
      assertEquals(TargetFields.class.getName(), injected.getName());
      assertSame(isolated, injected.getClassLoader());
      assertNotSame(TargetFields.class, injected);
      Object instance = target.getConstructor(String.class).newInstance("n");
      assertEquals("n", injected.getMethod("$$DD$$__name").invoke(instance));
    } finally {
      instrumentation.removeTransformer(transformer);
    }
  }

  private static byte[] classBytes(String className) throws IOException {
    String resource = className.replace('.', '/') + ".class";
    try (InputStream in =
        FieldAccessorInjectionForkedTest.class.getClassLoader().getResourceAsStream(resource)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int n; (n = in.read(buffer)) > 0; ) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  private static ClassFileTransformer installTransformer(
      Instrumentation instrumentation, InstrumenterModule module) {
    TypePoolFacade.registerAsSupplier();
    MemoizedMatchers.registerAsSupplier();

    InstrumenterIndex instrumenterIndex = InstrumenterIndex.readIndex();
    InstrumenterState.initialize(instrumenterIndex.instrumentationCount());
    CombiningTransformerBuilder transformerBuilder =
        new CombiningTransformerBuilder(
            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(none()),
            instrumenterIndex,
            EnumSet.of(InstrumenterModule.TargetSystem.TRACING),
            false);
    transformerBuilder.applyInstrumentation(module);
    InstrumenterState.resetDefaultState();

    ClassFileTransformer transformer = transformerBuilder.installOn(instrumentation);
    SharedTypePools.endInstall();
    return transformer;
  }

  private static final class AccessorModule extends InstrumenterModule.Tracing
      implements Instrumenter.ForSingleType, Instrumenter.WithStructuralChange {

    private AccessorModule() {
      super("field-accessor-injection-test");
    }

    @Override
    public String instrumentedType() {
      return TARGET_CLASS_NAME;
    }

    @Override
    public void typeAdvice(TypeTransformer transformer) {
      transformer.installFieldAccessors(TargetFields.class);
    }

    @Override
    public Class<?> structuralChangeMarker() {
      return TargetFields.class;
    }

    public static class Muzzle {
      public static ReferenceMatcher create() {
        return ReferenceMatcher.NO_REFERENCES;
      }
    }
  }
}
