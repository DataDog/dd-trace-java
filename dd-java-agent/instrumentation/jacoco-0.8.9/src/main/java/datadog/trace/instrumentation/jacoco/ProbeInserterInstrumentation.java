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
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.objectweb.asm.Opcodes;

@AutoService(InstrumenterModule.class)
public class ProbeInserterInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {
  public ProbeInserterInstrumentation() {
    super("jacoco");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isCiVisibilityCoverageLinesEnabled();
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".MethodVisitorWrapper"};
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
                                .and(takesArgument(0, Object.class))))
                    .and(
                        declaresMethod(
                            named("visitVarInsn")
                                .and(takesArguments(2))
                                .and(takesArgument(0, int.class))
                                .and(takesArgument(1, int.class))))));
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("visitCode")).and(takesArguments(0)),
        getClass().getName() + "$VisitCodeAdvice");
  }

  public static class VisitCodeAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.FieldValue(value = "mv") final Object mv,
        @Advice.FieldValue(value = "arrayStrategy") final Object arrayStrategy,
        @Advice.FieldValue(value = "variable") final int variable,
        @Advice.FieldValue(value = "accessorStackSize", readOnly = false) int accessorStackSize)
        throws Throwable {
      Field classNameField = arrayStrategy.getClass().getDeclaredField("className");
      classNameField.setAccessible(true);
      String className = (String) classNameField.get(arrayStrategy);

      String[] excludedPackages = Config.get().getCiVisibilityCodeCoverageExcludedPackages();
      for (String excludedPackage : excludedPackages) {
        if (className.startsWith(excludedPackage)) {
          return;
        }
      }

      String[] includedPackages = Config.get().getCiVisibilityCodeCoverageIncludedPackages();
      if (includedPackages.length > 0) {
        boolean included = false;
        for (String includedPackage : includedPackages) {
          if (className.startsWith(includedPackage)) {
            included = true;
            break;
          }
        }
        if (!included) {
          return;
        }
      }

      Field classIdField = arrayStrategy.getClass().getDeclaredField("classId");
      classIdField.setAccessible(true);
      long classId = classIdField.getLong(arrayStrategy);

      MethodVisitorWrapper methodVisitor = MethodVisitorWrapper.wrap(mv);

      // ALOAD variable — load JaCoCo's shared boolean[] array
      methodVisitor.visitVarInsn(Opcodes.ALOAD, variable);
      // LDC ClassName.class — push Class reference
      methodVisitor.pushClass(className);
      // LDC classId — push long classId
      methodVisitor.visitLdcInsn(classId);
      // INVOKESTATIC resolveProbeArray(boolean[], Class, long) → boolean[]
      methodVisitor.visitMethodInsn(
          Opcodes.INVOKESTATIC,
          "datadog/trace/api/civisibility/coverage/CoveragePerTestBridge",
          "resolveProbeArray",
          "([ZLjava/lang/Class;J)[Z",
          false);
      // ASTORE variable — replace local variable with per-test array
      methodVisitor.visitVarInsn(Opcodes.ASTORE, variable);

      // Ensure enough stack space for our bytecodes (1 ref + 1 ref + 1 long = 4 slots)
      accessorStackSize = Math.max(accessorStackSize, 4);
    }
  }
}
