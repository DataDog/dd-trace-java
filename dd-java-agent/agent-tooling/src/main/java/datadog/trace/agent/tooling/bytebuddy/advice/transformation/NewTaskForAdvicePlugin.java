package datadog.trace.agent.tooling.bytebuddy.advice.transformation;

import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.named;

import java.lang.reflect.Method;
import java.util.concurrent.AbstractExecutorService;
import lombok.SneakyThrows;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.ByteCodeElement;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.bytecode.StackManipulation;
import net.bytebuddy.implementation.bytecode.member.MethodInvocation;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.CompoundList;

/**
 * This Plugin is responsible for modifying WrapRunnableAsNewTaskInstrumentation's advice so it is
 * able to make a call to a protected method.
 */
public final class NewTaskForAdvicePlugin implements Plugin {
  Method newTaskForMethod;

  @SneakyThrows
  public NewTaskForAdvicePlugin() {
    newTaskForMethod =
        AbstractExecutorService.class.getDeclaredMethod("newTaskFor", Runnable.class, Object.class);
  }

  @Override
  public boolean matches(TypeDescription target) {
    return named(
            "datadog.trace.instrumentation.java.concurrent.WrapRunnableAsNewTaskInstrumentation$Wrap")
        .matches(target);
  }

  @Override
  public DynamicType.Builder<?> apply(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassFileLocator classFileLocator) {
    return builder.visit(
        MemberSubstitution.relaxed()
            .method(named("superNewTaskFor"))
            // https://github.com/raphw/byte-buddy/issues/976
            // .replaceWith(newTaskForMethod)
            .replaceWith(new VisibilityIgnoringSubstitution(newTaskForMethod))
            .on(any()));
  }

  @Override
  public void close() {}

  // This is a hack until https://github.com/raphw/byte-buddy/issues/976 is resolved.
  private static final class VisibilityIgnoringSubstitution
      implements MemberSubstitution.Substitution.Factory {
    private final MethodDescription methodDescription;

    public VisibilityIgnoringSubstitution(Method method) {
      this.methodDescription = new MethodDescription.ForLoadedMethod(method);
    }

    @Override
    public MemberSubstitution.Substitution make(
        TypeDescription instrumentedType, MethodDescription instrumentedMethod, TypePool typePool) {
      return new VisibilityIgnoringSubstitutionInvocation(
          instrumentedType,
          new MemberSubstitution.Substitution.ForMethodInvocation.MethodResolver.Simple(
              methodDescription));
    }
  }

  private static final class VisibilityIgnoringSubstitutionInvocation
      extends MemberSubstitution.Substitution.ForMethodInvocation {

    /** The index of the this reference within a non-static method. */
    private static final int THIS_REFERENCE = 0;

    private final MethodResolver methodResolver;

    public VisibilityIgnoringSubstitutionInvocation(
        TypeDescription instrumentedType, MethodResolver methodResolver) {
      super(instrumentedType, methodResolver);
      this.methodResolver = methodResolver;
    }

    // Copied from net.bytebuddy.asm.MemberSubstitution.Substitution.ForMethodInvocation so the
    // visibility restrictions could be removed and other specializations.
    @Override
    public StackManipulation resolve(
        TypeDescription targetType,
        ByteCodeElement target,
        TypeList.Generic parameters,
        TypeDescription.Generic result,
        int freeOffset) {
      MethodDescription methodDescription =
          methodResolver.resolve(targetType, target, parameters, result);

      TypeList.Generic mapped =
          methodDescription.isStatic()
              ? methodDescription.getParameters().asTypeList()
              : new TypeList.Generic.Explicit(
                  CompoundList.of(
                      methodDescription.getDeclaringType(),
                      methodDescription.getParameters().asTypeList()));

      if (!methodDescription.getReturnType().asErasure().isAssignableTo(result.asErasure())) {
        throw new IllegalStateException(
            "Cannot assign return value of " + methodDescription + " to " + result);
      } else if (mapped.size() != parameters.size()) {
        throw new IllegalStateException(
            "Cannot invoke " + methodDescription + " on " + parameters.size() + " parameters");
      }

      return MethodInvocation.invoke(methodDescription)
          .virtual(mapped.get(THIS_REFERENCE).asErasure());
    }
  }
}
