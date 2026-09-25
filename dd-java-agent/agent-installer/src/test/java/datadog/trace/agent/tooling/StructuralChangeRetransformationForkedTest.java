package datadog.trace.agent.tooling;

import static datadog.trace.util.CollectionUtils.appendToArray;
import static datadog.trace.util.CollectionUtils.arrayContains;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.memoize.MemoizedMatchers;
import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;
import org.junit.jupiter.api.Test;

class StructuralChangeRetransformationForkedTest {

  private static final String TARGET_CLASS_NAME =
      "datadog.trace.agent.tooling.GeneratedStructuralChangeTarget";

  @Test
  void reappliesStructuralChangeWhenRetransformingClassModifiedOnInitialLoad() throws Exception {
    Instrumentation instrumentation = ByteBuddyAgent.install();

    StructuralChangeInstrumentation structuralChange = new StructuralChangeInstrumentation();
    ClassFileTransformer transformer = installTransformer(instrumentation, structuralChange);
    try {
      Class<?> targetClass = loadTargetClass();

      assertEquals(1, structuralChange.transformations.get());
      assertTrue(StructuralChangeMarker.class.isAssignableFrom(targetClass));

      instrumentation.retransformClasses(targetClass);

      assertEquals(2, structuralChange.transformations.get());
      assertTrue(StructuralChangeMarker.class.isAssignableFrom(targetClass));
    } finally {
      instrumentation.removeTransformer(transformer);
    }
  }

  @Test
  void skipsStructuralChangeForClassLoadedBeforeTransformerInstallation() throws Exception {
    Instrumentation instrumentation = ByteBuddyAgent.install();

    Class<?> targetClass = loadTargetClass();

    StructuralChangeInstrumentation structuralChange = new StructuralChangeInstrumentation();
    ClassFileTransformer transformer = installTransformer(instrumentation, structuralChange);
    try {
      assertEquals(0, structuralChange.transformations.get());
      assertFalse(StructuralChangeMarker.class.isAssignableFrom(targetClass));

      instrumentation.retransformClasses(targetClass);

      assertEquals(0, structuralChange.transformations.get());
      assertFalse(StructuralChangeMarker.class.isAssignableFrom(targetClass));
    } finally {
      instrumentation.removeTransformer(transformer);
    }
  }

  private ClassFileTransformer installTransformer(
      Instrumentation instrumentation, StructuralChangeInstrumentation structuralChange) {
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
    transformerBuilder.applyInstrumentation(structuralChange);
    InstrumenterState.resetDefaultState();

    ClassFileTransformer transformer = transformerBuilder.installOn(instrumentation);
    SharedTypePools.endInstall();
    return transformer;
  }

  private Class<?> loadTargetClass() {
    return new ByteBuddy()
        .subclass(Object.class)
        .name(TARGET_CLASS_NAME)
        .make()
        .load(getClass().getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
        .getLoaded();
  }

  public interface StructuralChangeMarker {}

  private static final class StructuralChangeInstrumentation extends InstrumenterModule.Tracing
      implements Instrumenter.ForSingleType,
          Instrumenter.WithStructuralChange,
          Instrumenter.HasMethodAdvice {

    private final AtomicInteger transformations = new AtomicInteger();

    private StructuralChangeInstrumentation() {
      super("structural-change-retransformation-test");
    }

    @Override
    public String instrumentedType() {
      return TARGET_CLASS_NAME;
    }

    @Override
    public void typeAdvice(TypeTransformer transformer) {
      transformer.applyAdvice(
          (builder, typeDescription, classLoader, module, protectionDomain) -> {
            transformations.incrementAndGet();
            return builder.visit(new AddStructuralMarkerVisitor());
          });
    }

    @Override
    public void methodAdvice(MethodTransformer transformer) {}

    @Override
    public Class<?> structuralChangeMarker() {
      return StructuralChangeMarker.class;
    }

    public static class Muzzle {
      public static ReferenceMatcher create() {
        return ReferenceMatcher.NO_REFERENCES;
      }
    }
  }

  private static final class AddStructuralMarkerVisitor implements AsmVisitorWrapper {

    private static final String MARKER_NAME = Type.getInternalName(StructuralChangeMarker.class);

    @Override
    public int mergeWriter(int flags) {
      return flags;
    }

    @Override
    public int mergeReader(int flags) {
      return flags;
    }

    @Override
    public ClassVisitor wrap(
        TypeDescription instrumentedType,
        ClassVisitor classVisitor,
        Implementation.Context implementationContext,
        TypePool typePool,
        FieldList<FieldDescription.InDefinedShape> fields,
        MethodList<?> methods,
        int writerFlags,
        int readerFlags) {
      return new ClassVisitor(OpenedClassReader.ASM_API, classVisitor) {
        @Override
        public void visit(
            int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces) {
          if (!arrayContains(interfaces, MARKER_NAME)) {
            interfaces = appendToArray(interfaces, MARKER_NAME);
          }
          super.visit(version, access, name, signature, superName, interfaces);
        }
      };
    }
  }
}
