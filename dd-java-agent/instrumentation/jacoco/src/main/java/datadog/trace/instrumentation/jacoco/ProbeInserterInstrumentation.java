package datadog.trace.instrumentation.jacoco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

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
    implements Instrumenter.ForTypeHierarchy, Instrumenter.WithTypeStructure {
  public ProbeInserterInstrumentation() {
    super("jacoco");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ReflectiveMethodVisitor"};
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(
            named("mv")
                .and(
                    fieldType(
                        nameStartsWith("org.jacoco.agent.rt.internal")
                            .and(nameEndsWith(".asm.MethodVisitor"))
                            .and(
                                declaresMethod(
                                    named("visitMethodInsn")
                                        .and(takesArguments(5))
                                        .and(takesArgument(0, int.class))
                                        .and(takesArgument(1, String.class))
                                        .and(takesArgument(2, String.class))
                                        .and(takesArgument(3, String.class))
                                        .and(takesArgument(4, boolean.class))))
                            .and(
                                declaresMethod(
                                    named("visitInsn")
                                        .and(takesArguments(1))
                                        .and(takesArgument(0, int.class))))
                            .and(
                                declaresMethod(
                                    named("visitIntInsn")
                                        .and(takesArguments(2))
                                        .and(takesArgument(0, int.class))
                                        .and(takesArgument(1, int.class))))
                            .and(
                                declaresMethod(
                                    named("visitLdcInsn")
                                        .and(takesArguments(1))
                                        .and(takesArgument(0, Object.class)))))))
        .and(
            declaresField(
                named("arrayStrategy")
                    .and(
                        fieldType(
                            implementsInterface(
                                    nameStartsWith("org.jacoco.agent.rt.internal")
                                        .and(
                                            nameEndsWith(
                                                ".core.internal.instr.IProbeArrayStrategy")))
                                .and(declaresField(named("className").and(fieldType(String.class))))
                                .and(
                                    declaresField(named("classId").and(fieldType(long.class))))))));
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.jacoco.agent.rt.IAgent";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // The jacoco javaagent jar that is published relocates internal classes to an "obfuscated"
    // package name ex. org.jacoco.agent.rt.internal_72ddf3b.core.internal.instr.ProbeInserter
    return nameStartsWith("org.jacoco.agent.rt.internal")
        .and(nameEndsWith(".core.internal.instr.ProbeInserter"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("visitMaxs")).and(takesArguments(2)).and(takesArgument(0, int.class)),
        getClass().getName() + "$VisitMaxsAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("insertProbe"))
            .and(takesArguments(1))
            .and(takesArgument(0, int.class)),
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
