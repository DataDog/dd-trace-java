package testdog.trace.instrumentation.lambda;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.any;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

/** Test-only consumer of generated Runnable lambda transformation. */
@AutoService(InstrumenterModule.class)
public final class TestRunnableLambdaInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForLambda, Instrumenter.HasTypeAdvice {

  static final String ADVICE_MARKER_FIELD = "__datadog_test_for_lambda";

  public TestRunnableLambdaInstrumentation() {
    super("test-runnable-lambda");
  }

  @Override
  public String lambdaInterface() {
    return Runnable.class.getName();
  }

  @Override
  public ElementMatcher<TypeDescription> lambdaMatcher() {
    return any();
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new AdviceMarkerVisitor());
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  private static final class AdviceMarkerVisitor extends AsmVisitorWrapper.AbstractBase {
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
        public void visitEnd() {
          FieldVisitor marker =
              cv.visitField(
                  Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, ADVICE_MARKER_FIELD, "Z", null, null);
          if (marker != null) {
            marker.visitEnd();
          }
          super.visitEnd();
        }
      };
    }
  }
}
