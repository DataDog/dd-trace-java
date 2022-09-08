package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.util.CallSiteConstants.CALL_SITE_ADVICE_CLASS;
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER;

import datadog.trace.plugin.csi.AdvicePointcutParser;
import datadog.trace.plugin.csi.TypeResolver;
import datadog.trace.plugin.csi.TypeResolver.ResolutionException;
import datadog.trace.plugin.csi.Validatable;
import datadog.trace.plugin.csi.ValidationContext;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/** Description of a class annotated with {@link datadog.trace.agent.tooling.csi.CallSite} */
public class CallSiteSpecification implements Validatable {

  private final Type clazz;
  private final List<AdviceSpecification> advices;
  private final Type spi;
  private final Type[] helpers;

  public CallSiteSpecification(
      @Nonnull final Type clazz,
      @Nonnull final List<AdviceSpecification> advices,
      @Nonnull final Type spi,
      @Nonnull final Set<Type> helpers) {
    this.clazz = clazz;
    this.advices = advices;
    this.spi = spi;
    this.helpers = helpers.toArray(new Type[0]);
  }

  @Override
  public void validate(@Nonnull final ValidationContext context) {
    final TypeResolver typeResolver = context.getContextProperty(TYPE_RESOLVER);
    try {
      if (!CALL_SITE_ADVICE_CLASS.equals(spi.getClassName())) {
        Class<?> spiClass = typeResolver.resolveType(spi);
        if (!spiClass.isInterface()) {
          context.addError(ErrorCode.CALL_SITE_SPI_SHOULD_BE_AN_INTERFACE, spiClass);
        } else {
          if (spiClass.getDeclaredMethods().length > 0) {
            context.addError(ErrorCode.CALL_SITE_SPI_SHOULD_BE_EMPTY, spiClass);
          }
        }
      }
    } catch (ResolutionException e) {
      e.getErrors().forEach(context::addError);
    }
    if (advices.isEmpty()) {
      context.addError(ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS);
    }
  }

  public Type getClazz() {
    return clazz;
  }

  public Type getSpi() {
    return spi;
  }

  public Type[] getHelpers() {
    return helpers;
  }

  public Stream<AdviceSpecification> getAdvices() {
    return advices.stream();
  }

  /**
   * Description of a method annotated with {@link datadog.trace.agent.tooling.csi.CallSite.After},
   * {@link datadog.trace.agent.tooling.csi.CallSite.Before} or {@link
   * datadog.trace.agent.tooling.csi.CallSite.Around}
   */
  public abstract static class AdviceSpecification implements Validatable {

    protected final MethodType advice;
    private final Map<Integer, ParameterSpecification> parameters;
    private final String signature;
    protected MethodType pointcut;
    protected Executable pointcutMethod;

    public AdviceSpecification(
        @Nonnull final MethodType advice,
        @Nonnull final Map<Integer, ParameterSpecification> parameters,
        @Nonnull final String signature) {
      this.advice = advice;
      this.parameters = new TreeMap<>(parameters);
      this.signature = signature;
    }

    @Override
    public void validate(@Nonnull final ValidationContext context) {
      validatePointcut(context);
      validateAdvice(context);
      validateCompatibility(context);
    }

    protected void validateCompatibility(final ValidationContext context) {
      final TypeResolver typeResolver = context.getContextProperty(TYPE_RESOLVER);
      // return type
      if (!Type.VOID_TYPE.equals(advice.getMethodType().getReturnType())) {
        try {
          Type pointcutType =
              pointcut.isConstructor()
                  ? pointcut.getOwner()
                  : pointcut.getMethodType().getReturnType();
          Type adviceType = advice.getMethodType().getReturnType();
          if (!typeResolver
              .resolveType(adviceType)
              .isAssignableFrom(typeResolver.resolveType(pointcutType))) {
            context.addError(
                ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE, pointcutType, adviceType);
          }
        } catch (ResolutionException e) {
          e.getErrors().forEach(context::addError);
        }
      }
      parameters.forEach(
          (index, spec) -> {
            try {
              Type pointcutType;
              if (spec instanceof ThisSpecification) {
                pointcutType = pointcut.getOwner();
              } else if (spec instanceof ArgumentSpecification) {
                ArgumentSpecification argument = (ArgumentSpecification) spec;
                pointcutType = pointcut.getMethodType().getArgumentTypes()[argument.getIndex()];
              } else {
                pointcutType = pointcut.getMethodType().getReturnType();
              }
              Type adviceType = advice.getMethodType().getArgumentTypes()[index];
              if (!typeResolver
                  .resolveType(adviceType)
                  .isAssignableFrom(typeResolver.resolveType(pointcutType))) {
                context.addError(
                    ErrorCode.ADVICE_METHOD_PARAMETER_NOT_COMPATIBLE,
                    index,
                    pointcutType,
                    adviceType);
              }
            } catch (ResolutionException e) {
              e.getErrors().forEach(context::addError);
            }
          });
    }

    protected void validateAdvice(@Nonnull final ValidationContext context) {
      try {
        validateAdviceParameters(context);
        final TypeResolver typeResolver = context.getContextProperty(TYPE_RESOLVER);
        final Method executable = (Method) typeResolver.resolveMethod(advice);
        final int access = executable.getModifiers();
        if (!Modifier.isPublic(access) || !Modifier.isStatic(access)) {
          context.addError(ErrorCode.ADVICE_METHOD_NOT_STATIC_AND_PUBLIC, this);
        }
      } catch (ResolutionException e) {
        e.getErrors().forEach(context::addError);
      }
    }

    protected void validateAdviceParameters(@Nonnull final ValidationContext context) {
      final Type[] arguments = advice.getMethodType().getArgumentTypes();
      boolean thisFound = false, returnFound = false;
      final Set<Integer> parametersFound = new HashSet<>();
      for (int i = 0; i < arguments.length; i++) {
        ParameterSpecification spec = parameters.get(i);
        if (spec == null) {
          context.addError(ErrorCode.ADVICE_PARAMETER_NOT_ANNOTATED, i);
        } else {
          if (spec instanceof ThisSpecification) {
            if (thisFound) {
              context.addError(ErrorCode.ADVICE_PARAMETER_THIS_DUPLICATED, i);
            }
            if (i != 0) {
              context.addError(ErrorCode.ADVICE_PARAMETER_THIS_SHOULD_BE_FIRST, i);
            }
            if (isStaticPointcut()) {
              context.addError(ErrorCode.ADVICE_PARAMETER_THIS_ON_STATIC_METHOD, i);
            }
            thisFound = true;
          } else if (spec instanceof ReturnSpecification) {
            if (returnFound) {
              context.addError(ErrorCode.ADVICE_PARAMETER_RETURN_DUPLICATED, i);
            }
            if (i != arguments.length - 1) {
              context.addError(ErrorCode.ADVICE_PARAMETER_RETURN_SHOULD_BE_LAST, i);
            }
            returnFound = true;
          } else {
            ArgumentSpecification argument = (ArgumentSpecification) spec;
            if (argument.getIndex() >= arguments.length) {
              context.addError(
                  ErrorCode.ADVICE_PARAMETER_ARGUMENT_OUT_OF_BOUNDS, i, argument.getIndex());
            }
            if (parametersFound.contains(argument.getIndex())) {
              context.addError(
                  ErrorCode.ADVICE_PARAMETER_ARGUMENT_DUPLICATED, i, argument.getIndex());
            }
            if (!parametersFound.isEmpty()
                && Collections.max(parametersFound) >= argument.getIndex()) {
              context.addError(
                  ErrorCode.ADVICE_PARAMETER_ARGUMENT_SHOULD_BE_IN_ORDER, i, argument.getIndex());
            }
            parametersFound.add(argument.getIndex());
          }
        }
      }
    }

    protected void validatePointcut(@Nonnull final ValidationContext context) {
      try {
        final TypeResolver typeResolver = context.getContextProperty(TYPE_RESOLVER);
        if (pointcut.isConstructor()
            && !Type.VOID_TYPE.equals(pointcut.getMethodType().getReturnType())) {
          context.addError(ErrorCode.ADVICE_POINTCUT_CONSTRUCTOR_NOT_VOID, pointcut);
        }
        pointcutMethod = typeResolver.resolveMethod(pointcut);
      } catch (ResolutionException e) {
        e.getErrors().forEach(context::addError);
      }
    }

    public void parseSignature(@Nonnull final AdvicePointcutParser parser) {
      pointcut = parser.parse(signature);
    }

    public MethodType getAdvice() {
      return advice;
    }

    public MethodType getPointcut() {
      return pointcut;
    }

    public String getSignature() {
      return signature;
    }

    public boolean isStaticPointcut() {
      return pointcutMethod != null && Modifier.isStatic(pointcutMethod.getModifiers());
    }

    public ParameterSpecification firstParameter() {
      if (parameters.isEmpty()) {
        return null;
      }
      int first = Collections.min(parameters.keySet());
      return parameters.get(first);
    }

    public ParameterSpecification lastParameter() {
      if (parameters.isEmpty()) {
        return null;
      }
      int last = Collections.max(parameters.keySet());
      return parameters.get(last);
    }

    public boolean hasThis() {
      return parameters.values().stream().anyMatch(it -> it instanceof ThisSpecification);
    }

    public boolean hasReturn() {
      return parameters.values().stream().anyMatch(it -> it instanceof ReturnSpecification);
    }

    public boolean isConstructor() {
      return pointcut.isConstructor();
    }

    public Stream<ArgumentSpecification> getArguments() {
      return parameters.values().stream()
          .filter(it -> it instanceof ArgumentSpecification)
          .map(it -> (ArgumentSpecification) it);
    }
  }

  public static final class BeforeSpecification extends AdviceSpecification {
    public BeforeSpecification(
        @Nonnull final MethodType advice,
        @Nonnull final Map<Integer, ParameterSpecification> parameters,
        @Nonnull final String signature) {
      super(advice, parameters, signature);
    }

    @Override
    protected void validateAdvice(@Nonnull final ValidationContext context) {
      if (!Type.VOID_TYPE.equals(advice.getMethodType().getReturnType())) {
        context.addError(ErrorCode.ADVICE_BEFORE_SHOULD_RETURN_VOID, advice);
      }
      if (hasReturn()) {
        context.addError(ErrorCode.ADVICE_BEFORE_SHOULD_NOT_CONTAIN_RETURN);
      }
      if (pointcut.isConstructor() && hasThis()) {
        context.addError(ErrorCode.ADVICE_BEFORE_CTOR_SHOULD_NOT_CONTAIN_THIS);
      }
      super.validateAdvice(context);
    }
  }

  public static final class AroundSpecification extends AdviceSpecification {
    public AroundSpecification(
        @Nonnull final MethodType advice,
        @Nonnull final Map<Integer, ParameterSpecification> parameters,
        @Nonnull final String signature) {
      super(advice, parameters, signature);
    }

    @Override
    protected void validateAdvice(@Nonnull final ValidationContext context) {
      if (Type.VOID_TYPE.equals(advice.getMethodType().getReturnType())) {
        context.addError(ErrorCode.ADVICE_AROUND_SHOULD_NOT_RETURN_VOID);
      }
      if (hasReturn()) {
        context.addError(ErrorCode.ADVICE_AROUND_SHOULD_NOT_CONTAIN_RETURN);
      }
      super.validateAdvice(context);
    }

    @Override
    protected void validatePointcut(@Nonnull final ValidationContext context) {
      if (pointcut.isConstructor()) {
        context.addError(ErrorCode.ADVICE_AROUND_POINTCUT_CTOR);
      }
      super.validatePointcut(context);
    }
  }

  public static final class AfterSpecification extends AdviceSpecification {
    public AfterSpecification(
        @Nonnull final MethodType advice,
        @Nonnull final Map<Integer, ParameterSpecification> parameters,
        @Nonnull final String signature) {
      super(advice, parameters, signature);
    }

    @Override
    protected void validateAdvice(@Nonnull final ValidationContext context) {
      if (Type.VOID_TYPE.equals(advice.getMethodType().getReturnType())) {
        context.addError(ErrorCode.ADVICE_AFTER_SHOULD_NOT_RETURN_VOID);
      }
      if (pointcut.isConstructor()) {
        if (!(firstParameter() instanceof ThisSpecification)) {
          context.addError(ErrorCode.ADVICE_AFTER_CTOR_FIRST_ARG_SHOULD_BE_THIS);
        }
      } else {
        if (!(lastParameter() instanceof ReturnSpecification)) {
          context.addError(ErrorCode.ADVICE_AFTER_LAST_ARG_SHOULD_BE_RETURN);
        }
      }
      super.validateAdvice(context);
    }
  }

  /**
   * Description of a method parameter annotated with {@link net.bytebuddy.asm.Advice.This}, {@link
   * net.bytebuddy.asm.Advice.Argument} or {@link net.bytebuddy.asm.Advice.Return}
   */
  public abstract static class ParameterSpecification {}

  public static final class ThisSpecification extends ParameterSpecification {}

  public static final class ReturnSpecification extends ParameterSpecification {}

  public static final class ArgumentSpecification extends ParameterSpecification {

    private int index;

    public int getIndex() {
      return index;
    }

    public void setIndex(final int index) {
      this.index = index;
    }
  }
}
