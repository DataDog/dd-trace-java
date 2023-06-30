package datadog.trace.instrumentation.jacoco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.declaresField;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.fieldType;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperClass;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
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
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems) && Config.get().isCiVisibilityCodeCoverageEnabled();
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ReflectiveMethodVisitor"};
  }

  @SuppressForbidden
  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    ElementMatcher<FieldDescription> methodVisitor = methodVisitor();
    return declaresField(arrayStrategy())
        .and(declaresField(methodVisitor).or(hasSuperClass(declaresField(methodVisitor))));
  }

  private ElementMatcher<FieldDescription> arrayStrategy() {
    ElementMatcher.Junction<TypeDescription> arrayStrategyType = arrayStrategyType();
    return named("arrayStrategy")
        .and(fieldType(arrayStrategyType.or(implementsInterface(arrayStrategyType))));
  }

  private static ElementMatcher.Junction<TypeDescription> arrayStrategyType() {
    return nameStartsWith("org.jacoco.agent.rt.internal")
        .and(nameEndsWith(".core.internal.instr.IProbeArrayStrategy"));
  }

  @SuppressForbidden
  private ElementMatcher<FieldDescription> methodVisitor() {
    return named("mv")
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
                                .and(takesArgument(0, Object.class))))));
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
      maxStack = maxStack + 2;
    }
  }

  public static class InsertProbeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.FieldValue(value = "mv") final Object mv,
        @Advice.FieldValue(value = "arrayStrategy") final Object arrayStrategy,
        @Advice.Argument(0) final int id)
        throws NoSuchMethodException, InvocationTargetException, IllegalAccessException,
            NoSuchFieldException, ClassNotFoundException {
      Field classNameField = arrayStrategy.getClass().getDeclaredField("className");
      classNameField.setAccessible(true);
      String className = (String) classNameField.get(arrayStrategy);

      String[] excludedClassnames = Config.get().getCiVisibilityJacocoPluginExcludedClassnames();
      for (String excludedClassname : excludedClassnames) {
        if (className.startsWith(excludedClassname)) {
          return;
        }
      }

      Field classIdField = arrayStrategy.getClass().getDeclaredField("classId");
      classIdField.setAccessible(true);
      Long classId = classIdField.getLong(arrayStrategy);

      ReflectiveMethodVisitor methodVisitor = ReflectiveMethodVisitor.wrap(mv);

      methodVisitor.visitLdcInsn(classId);
      methodVisitor.visitLdcInsn(className);
      methodVisitor.pushClass(className);
      methodVisitor.push(id);

      methodVisitor.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          "datadog/trace/api/civisibility/InstrumentationBridge",
          "currentCoverageProbeStoreRecord",
          "(JLjava/lang/String;Ljava/lang/Class;I)V",
          false);
    }
  }
}
