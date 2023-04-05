package datadog.trace.instrumentation.jacoco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.objectweb.asm.Opcodes;

@AutoService(Instrumenter.class)
public class ProbeInserterInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {
  public ProbeInserterInstrumentation() {
    super("jacoco");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ReflectiveMethodVisitor"};
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // The jacoco javaagent jar that is published relocates internal classes to an "obfuscated"
    // package name
    // ex. org.jacoco.agent.rt.internal_72ddf3b.core.internal.instr.ProbeInserter
    return nameStartsWith("org.jacoco.agent.rt.internal").and(nameEndsWith(".ProbeInserter"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("visitMaxs")), getClass().getName() + "$VisitMaxsAdvice");
    transformation.applyAdvice(
        isMethod().and(not(isStatic())).and(named("insertProbe")),
        getClass().getName() + "$InsertProbeAdvice");
  }

  public static class VisitMaxsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void enter(@Advice.Argument(value = 0, readOnly = false) int maxStack) {
      maxStack = maxStack + 1;
    }
  }

  public static class InsertProbeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.FieldValue(value = "mv") final Object mv,
        @Advice.FieldValue(value = "arrayStrategy") final Object arrayStrategy,
        @Advice.Argument(0) final int id)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException,
            NoSuchFieldException {
      Field classNameField = arrayStrategy.getClass().getDeclaredField("className");
      classNameField.setAccessible(true);
      String className = (String) classNameField.get(arrayStrategy);
      if (!className.startsWith("datadog/trace")) {

        Field classIdField = arrayStrategy.getClass().getDeclaredField("classId");
        classIdField.setAccessible(true);
        Long classId = classIdField.getLong(arrayStrategy);

        ReflectiveMethodVisitor methodVisitor = ReflectiveMethodVisitor.wrap(mv);

        methodVisitor.visitLdcInsn(classId);
        methodVisitor.visitLdcInsn(className);
        methodVisitor.push(id);
        methodVisitor.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "datadog/trace/api/civisibility/InstrumentationBridge",
            "currentCoverageProbeStoreRecord",
            "(JLjava/lang/String;I)V",
            false);
      }
    }
  }
}
