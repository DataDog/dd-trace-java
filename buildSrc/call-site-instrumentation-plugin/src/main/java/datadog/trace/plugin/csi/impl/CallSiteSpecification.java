package datadog.trace.plugin.csi.impl;

import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER;
import static datadog.trace.plugin.csi.util.CallSiteUtils.classNameToType;
import static java.util.Collections.emptyList;

import datadog.trace.plugin.csi.AdvicePointcutParser;
import datadog.trace.plugin.csi.TypeResolver;
import datadog.trace.plugin.csi.TypeResolver.ResolutionException;
import datadog.trace.plugin.csi.Validatable;
import datadog.trace.plugin.csi.ValidationContext;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import datadog.trace.plugin.csi.util.Types;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

/** Description of a class annotated with {@link datadog.trace.agent.tooling.csi.CallSite} */
public class CallSiteSpecification implements Validatable {

  private final Type clazz;
  private final List<AdviceSpecification> advices;
  private final Type[] spi;
  private final Enabled enabled;
  private final Type[] helpers;

  public CallSiteSpecification(
      @Nonnull final Type clazz,
      @Nonnull final List<AdviceSpecification> advices,
      @Nonnull final Set<Type> spi,
      @Nonnull final List<String> enabled,
      @Nonnull final Set<Type> helpers) {
    this.clazz = clazz;
    this.advices = advices;
    this.spi = spi.toArray(new Type[0]);
    this.enabled = enabled.isEmpty() ? null : new Enabled(enabled);
    this.helpers = helpers.toArray(new Type[0]);
  }

  @Override
  public void validate(@Nonnull final ValidationContext context) {
    final TypeResolver typeResolver = context.getContextProperty(TYPE_RESOLVER);
    try {
      for (Type spiType : spi) {
        Class<?> spiClass = typeResolver.resolveType(spiType);
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
    if (enabled != null) {
      try {
        final Method enabledMethod = (Method) typeResolver.resolveMethod(enabled.getMethod());
        final int modifiers = enabledMethod.getModifiers();
        if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers)) {
          context.addError(ErrorCode.CALL_SITE_ENABLED_SHOULD_BE_STATIC_AND_PUBLIC, enabledMethod);
        }
      } catch (ResolutionException e) {
        e.getErrors().forEach(context::addError);
      }
    }
    if (advices.isEmpty()) {
      context.addError(ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS);
    }
  }

  public Type getClazz() {
    return clazz;
  }

  public Type[] getSpi() {
    return spi;
  }

  public Enabled getEnabled() {
    return enabled;
  }

  public Type[] getHelpers() {
    return helpers;
  }

  public List<AdviceSpecification> getAdvices() {
    return advices;
  }

  /**
   * Description of a method annotated with {@link datadog.trace.agent.tooling.csi.CallSite.After},
   * {@link datadog.trace.agent.tooling.csi.CallSite.Before} or {@link
   * datadog.trace.agent.tooling.csi.CallSite.Around}
   */
  public abstract static class AdviceSpecification implements Validatable {

    protected final MethodType advice;
    private final Map<Integer /* param idx on the advice */, ParameterSpecification> parameters;
    protected final String signature;
    protected final boolean invokeDynamic;
    protected MethodType pointcut;
    protected Executable pointcutMethod;

    public AdviceSpecification(
        @Nonnull final MethodType advice,
        @Nonnull final Map<Integer, ParameterSpecification> parameters,
        @Nonnull final String signature,
        final boolean invokeDynamic) {
      this.advice = advice;
      this.parameters = new TreeMap<>(parameters);
      this.signature = signature;
      this.invokeDynamic = invokeDynamic;
    }

    @Override
    public void validate(@Nonnull final ValidationContext context) {
      validatePointcut(context);
      validateAdvice(context);
      validateCompatibility(context);
    }

    protected void validateCompatibility(final ValidationContext context) {
      try {
        final Type[] adviceArgumentTypes = advice.getMethodType().getArgumentTypes();
        final Set<Integer> pointcutParameters = new HashSet<>();
        for (int i = 0; i < pointcut.getMethodType().getArgumentTypes().length; i++) {
          pointcutParameters.add(i);
        }
        validateAllArgsSpecCompatibility(context, adviceArgumentTypes, pointcutParameters);
        if (isInvokeDynamic()) {
          validateInvokeDynamicConstCompatibility(context, adviceArgumentTypes, pointcutParameters);
          if (this instanceof AroundSpecification) {
            validateArgumentSpecCompatibility(context, adviceArgumentTypes, pointcutParameters);
          }
        } else {
          validateAdviceReturnTypeCompatibility(context);
          validateThisSpecCompatibility(context, adviceArgumentTypes);
          validateArgumentSpecCompatibility(context, adviceArgumentTypes, pointcutParameters);
          validateReturnSpecCompatibility(context, adviceArgumentTypes);
        }
      } catch (ResolutionException e) {
        e.getErrors().forEach(context::addError);
      }
    }

    private void validateArgumentSpecCompatibility(
        final ValidationContext context,
        final Type[] adviceArgumentTypes,
        final Set<Integer> pointcutParameters) {
      withParameter(
          ArgumentSpecification.class,
          (i, spec) -> {
            final Type argType = pointcut.getMethodType().getArgumentTypes()[spec.index];
            final Type advice = adviceArgumentTypes[i];
            if (!pointcutParameters.remove(spec.index)) {
              context.addError(ErrorCode.ADVICE_PARAMETER_ARGUMENT_OUT_OF_BOUNDS);
            }
            validateCompatibility(
                context, argType, advice, ErrorCode.ADVICE_METHOD_PARAM_NOT_COMPATIBLE, i);
          });
    }

    private void validateReturnSpecCompatibility(
        final ValidationContext context, final Type[] adviceArgumentTypes) {
      withParameter(
          ReturnSpecification.class,
          (i, spec) -> {
            final Type rType =
                pointcut.isConstructor()
                    ? pointcut.getOwner()
                    : pointcut.getMethodType().getReturnType();
            final Type advice = adviceArgumentTypes[i];
            validateCompatibility(
                context, rType, advice, ErrorCode.ADVICE_METHOD_PARAM_RETURN_NOT_COMPATIBLE, i);
          });
    }

    private void validateThisSpecCompatibility(
        final ValidationContext context, final Type[] adviceArgumentTypes) {
      withParameter(
          ThisSpecification.class,
          (i, spec) -> {
            final Type owner = pointcut.getOwner();
            final Type advice = adviceArgumentTypes[i];
            validateCompatibility(
                context, owner, advice, ErrorCode.ADVICE_METHOD_PARAM_THIS_NOT_COMPATIBLE, i);
          });
    }

    protected void validateAdviceReturnTypeCompatibility(final ValidationContext context) {
      if (!advice.isVoidReturn()) {
        final Type pointcutType =
            pointcut.isConstructor()
                ? pointcut.getOwner()
                : pointcut.getMethodType().getReturnType();
        final Type adviceType = advice.getMethodType().getReturnType();
        validateCompatibility(
            context, pointcutType, adviceType, ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE, -1);
      }
    }

    private void validateInvokeDynamicConstCompatibility(
        final ValidationContext context,
        final Type[] adviceArgumentTypes,
        final Set<Integer> pointcutParameters) {
      withParameter(
          InvokeDynamicConstantsSpecification.class,
          (i, spec) -> {
            final Type type = Types.OBJECT_ARRAY;
            final Type advice = adviceArgumentTypes[i];
            pointcutParameters.clear();
            validateCompatibility(
                context,
                type,
                advice,
                ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_NOT_COMPATIBLE,
                i);
          });
    }

    private void validateAllArgsSpecCompatibility(
        final ValidationContext context,
        final Type[] adviceArgumentTypes,
        final Set<Integer> pointcutParameters) {
      withParameter(
          AllArgsSpecification.class,
          (i, spec) -> {
            final Type type = Types.OBJECT_ARRAY;
            final Type advice = adviceArgumentTypes[i];
            pointcutParameters.clear();
            validateCompatibility(
                context, type, advice, ErrorCode.ADVICE_METHOD_PARAM_ALL_ARGS_NOT_COMPATIBLE, i);
          });
    }

    protected void validateCompatibility(
        final ValidationContext context,
        final Type pointcutType,
        final Type adviceType,
        final ErrorCode errorCode,
        final int index) {
      final TypeResolver typeResolver = context.getContextProperty(TYPE_RESOLVER);
      if (!typeResolver
          .resolveType(adviceType)
          .isAssignableFrom(typeResolver.resolveType(pointcutType))) {
        context.addError(errorCode, pointcutType, adviceType, index);
      }
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
      final Type[] adviceArguments = advice.getMethodType().getArgumentTypes();
      boolean thisFound = false,
          returnFound = false,
          allArgsFound = false,
          argumentFound = false,
          dynamicConstantsFound = false;
      for (int i = 0; i < adviceArguments.length; i++) {
        ParameterSpecification spec = parameters.get(i);
        if (spec == null) {
          context.addError(ErrorCode.ADVICE_PARAMETER_NOT_ANNOTATED, i);
        } else {
          if (spec instanceof ThisSpecification) {
            validateThisSpec(context, thisFound, i);
            thisFound = true;
          } else if (spec instanceof ReturnSpecification) {
            validateReturnSpec(context, returnFound, i);
            returnFound = true;
          } else if (spec instanceof AllArgsSpecification) {
            validateAllArgsSpec(context, allArgsFound, argumentFound, i);
            allArgsFound = true;
          } else if (spec instanceof InvokeDynamicConstantsSpecification) {
            validateInvokeDynamicConstSpec(context, dynamicConstantsFound, i);
            dynamicConstantsFound = true;
          } else {
            validateArgumentSpec(context, allArgsFound, i);
            argumentFound = true;
          }
        }
      }
    }

    private void validateArgumentSpec(
        final ValidationContext context, final boolean allArgsFound, final int i) {
      if (allArgsFound) {
        context.addError(ErrorCode.ADVICE_PARAMETER_ALL_ARGS_MIXED, i);
      }
      if (isInvokeDynamic() && !(this instanceof AroundSpecification)) {
        context.addError(ErrorCode.ADVICE_PARAMETER_ON_INVOKE_DYNAMIC, i);
      }
    }

    private void validateInvokeDynamicConstSpec(
        final ValidationContext context, final boolean dynamicConstantsFound, final int i) {
      if (dynamicConstantsFound) {
        context.addError(ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_DUPLICATED, i);
      }
      if (!isInvokeDynamic()) {
        context.addError(
            ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_ON_NON_INVOKE_DYNAMIC, i);
      }
      if (!(this instanceof AfterSpecification)) {
        context.addError(ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_NON_AFTER_ADVICE, i);
      }
      final Type[] arguments = advice.getMethodType().getArgumentTypes();
      if (i != arguments.length - 1) {
        context.addError(ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_SHOULD_BE_LAST, i);
      }
    }

    private void validateAllArgsSpec(
        final ValidationContext context,
        final boolean allArgsFound,
        final boolean argumentFound,
        final int i) {
      if (allArgsFound) {
        context.addError(ErrorCode.ADVICE_PARAMETER_ALL_ARGS_DUPLICATED, i);
      }
      if (argumentFound) {
        context.addError(ErrorCode.ADVICE_PARAMETER_ALL_ARGS_MIXED, i);
      }
    }

    private void validateReturnSpec(
        final ValidationContext context, final boolean returnFound, final int i) {
      if (returnFound) {
        context.addError(ErrorCode.ADVICE_PARAMETER_RETURN_DUPLICATED, i);
      }
      final Type[] arguments = advice.getMethodType().getArgumentTypes();
      if (i != arguments.length - 1) {
        if (isInvokeDynamic()) {
          if (i != arguments.length - 2
              && !(parameters.get(arguments.length - 1)
                  instanceof InvokeDynamicConstantsSpecification)) {}

        } else {
          context.addError(ErrorCode.ADVICE_PARAMETER_RETURN_SHOULD_BE_LAST, i);
        }
      }
      if (!(this instanceof AfterSpecification)) {
        context.addError(ErrorCode.ADVICE_PARAMETER_RETURN_NON_AFTER_ADVICE, i);
      }
    }

    private void validateThisSpec(
        final ValidationContext context, final boolean thisFound, final int i) {
      if (thisFound) {
        context.addError(ErrorCode.ADVICE_PARAMETER_THIS_DUPLICATED, i);
      }
      if (i != 0) {
        context.addError(ErrorCode.ADVICE_PARAMETER_THIS_SHOULD_BE_FIRST, i);
      }
      if (isStaticPointcut()) {
        context.addError(ErrorCode.ADVICE_PARAMETER_THIS_ON_STATIC_METHOD, i);
      }
      if (isInvokeDynamic()) {
        context.addError(ErrorCode.ADVICE_PARAMETER_THIS_ON_INVOKE_DYNAMIC, i);
      }
    }

    protected void validatePointcut(@Nonnull final ValidationContext context) {
      if (pointcut != null) {
        try {
          final TypeResolver typeResolver = context.getContextProperty(TYPE_RESOLVER);
          if (pointcut.isConstructor() && !pointcut.isVoidReturn()) {
            context.addError(ErrorCode.ADVICE_POINTCUT_CONSTRUCTOR_NOT_VOID, pointcut);
          }
          pointcutMethod = typeResolver.resolveMethod(pointcut);
        } catch (ResolutionException e) {
          e.getErrors().forEach(context::addError);
        }
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

    public boolean isInvokeDynamic() {
      return invokeDynamic;
    }

    /* Whether not all of the pointcut arguments are consumed or they're not
     * consumed in sequential order or there are no pointcut arguments at all */
    public boolean isPositionalArguments() {
      if (parameters.isEmpty()) {
        return true;
      }
      if (pointcut.getMethodType().getArgumentTypes().length != getArguments().count()) {
        return true;
      }

      Iterator<ArgumentSpecification> iterator = getArguments().iterator();
      int i = 0;
      while (iterator.hasNext()) {
        ArgumentSpecification spec = iterator.next();
        if (spec.getIndex() != i) {
          return true;
        }
        i++;
      }
      return false;
    }

    public boolean includeThis() {
      if (findThis() != null) {
        return true;
      }
      final AllArgsSpecification allArgs = findAllArguments();
      return allArgs != null && allArgs.includeThis;
    }

    public ThisSpecification findThis() {
      return findParameter(ThisSpecification.class);
    }

    public ReturnSpecification findReturn() {
      return findParameter(ReturnSpecification.class);
    }

    public AllArgsSpecification findAllArguments() {
      return findParameter(AllArgsSpecification.class);
    }

    public InvokeDynamicConstantsSpecification findInvokeDynamicConstants() {
      return findParameter(InvokeDynamicConstantsSpecification.class);
    }

    private <E extends ParameterSpecification> void withParameter(
        final Class<E> spec, final BiConsumer<Integer, E> consumer) {
      parameters.entrySet().stream()
          .filter(entry -> spec.isInstance(entry.getValue()))
          .forEach(entry -> consumer.accept(entry.getKey(), spec.cast(entry.getValue())));
    }

    private <E extends ParameterSpecification> E findParameter(final Class<E> spec) {
      return parameters.values().stream()
          .filter(spec::isInstance)
          .map(spec::cast)
          .findFirst()
          .orElse(null);
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
        @Nonnull final String signature,
        final boolean invokeDynamic) {
      super(advice, parameters, signature, invokeDynamic);
    }

    @Override
    protected void validateAdvice(@Nonnull final ValidationContext context) {
      if (!advice.isVoidReturn()) {
        context.addError(ErrorCode.ADVICE_BEFORE_SHOULD_RETURN_VOID, advice);
      }
      if (findReturn() != null) {
        context.addError(ErrorCode.ADVICE_BEFORE_SHOULD_NOT_CONTAIN_RETURN);
      }
      if (pointcut.isConstructor() && includeThis()) {
        context.addError(ErrorCode.ADVICE_BEFORE_CTOR_SHOULD_NOT_CONTAIN_THIS);
      }
      super.validateAdvice(context);
    }

    @Override
    public String toString() {
      return "@CallSite.Before(" + signature + ")";
    }
  }

  public static final class AroundSpecification extends AdviceSpecification {
    public AroundSpecification(
        @Nonnull final MethodType advice,
        @Nonnull final Map<Integer, ParameterSpecification> parameters,
        @Nonnull final String signature,
        final boolean invokeDynamic) {
      super(advice, parameters, signature, invokeDynamic);
    }

    @Override
    protected void validateAdvice(@Nonnull final ValidationContext context) {
      if (advice.isVoidReturn()) {
        context.addError(ErrorCode.ADVICE_AROUND_SHOULD_NOT_RETURN_VOID);
      }
      if (findReturn() != null) {
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

    @Override
    public String toString() {
      return "@CallSite.Around(" + signature + ")";
    }
  }

  public static final class AfterSpecification extends AdviceSpecification {
    public AfterSpecification(
        @Nonnull final MethodType advice,
        @Nonnull final Map<Integer, ParameterSpecification> parameters,
        @Nonnull final String signature,
        final boolean invokeDynamic) {
      super(advice, parameters, signature, invokeDynamic);
    }

    @Override
    protected void validateAdvice(@Nonnull final ValidationContext context) {
      if (advice.isVoidReturn()) {
        context.addError(ErrorCode.ADVICE_AFTER_SHOULD_NOT_RETURN_VOID);
      }
      if (findReturn() == null) {
        context.addError(ErrorCode.ADVICE_AFTER_SHOULD_HAVE_RETURN);
      }
      if (!pointcut.isConstructor()) {
        if (!isStaticPointcut() && !includeThis()) {
          context.addError(ErrorCode.ADVICE_AFTER_SHOULD_HAVE_THIS);
        }
      } else {
        if (findAllArguments() == null) {
          context.addError(ErrorCode.ADVICE_AFTER_CONSTRUCTOR_ALL_ARGUMENTS);
        }
      }
      super.validateAdvice(context);
    }

    @Override
    public String toString() {
      return "@CallSite.After(" + signature + ")";
    }
  }

  /**
   * Description of a method parameter annotated with: {@link
   * datadog.trace.agent.tooling.csi.CallSite.This}, {@link
   * datadog.trace.agent.tooling.csi.CallSite.Argument}, {@link
   * datadog.trace.agent.tooling.csi.CallSite.AllArguments}, {@link
   * datadog.trace.agent.tooling.csi.CallSite.InvokeDynamicConstants}, {@link
   * datadog.trace.agent.tooling.csi.CallSite.Return}
   */
  public abstract static class ParameterSpecification {}

  public static final class ThisSpecification extends ParameterSpecification {
    @Override
    public String toString() {
      return "@This";
    }
  }

  public static final class ReturnSpecification extends ParameterSpecification {
    @Override
    public String toString() {
      return "@Return";
    }
  }

  public static final class AllArgsSpecification extends ParameterSpecification {

    private boolean includeThis;

    public boolean isIncludeThis() {
      return includeThis;
    }

    public void setIncludeThis(boolean includeThis) {
      this.includeThis = includeThis;
    }

    @Override
    public String toString() {
      return "@AllArguments(includeThis=" + includeThis + ")";
    }
  }

  public static final class ArgumentSpecification extends ParameterSpecification {

    private int index;

    public int getIndex() {
      return index;
    }

    public void setIndex(final int index) {
      this.index = index;
    }

    @Override
    public String toString() {
      return "@Argument(" + index + ")";
    }
  }

  public static final class InvokeDynamicConstantsSpecification extends ParameterSpecification {
    @Override
    public String toString() {
      return "@InvokeDynamicConstants";
    }
  }

  public static final class Enabled {
    private final MethodType method;
    private final List<String> arguments;

    public Enabled(final List<String> enabled) {
      this.arguments = enabled.size() <= 2 ? emptyList() : enabled.subList(2, enabled.size());
      this.method =
          new MethodType(
              classNameToType(enabled.get(0)),
              enabled.get(1),
              Type.getMethodType(Types.BOOLEAN, stringTypeArray(arguments.size())));
    }

    public MethodType getMethod() {
      return method;
    }

    public List<String> getArguments() {
      return arguments;
    }

    private Type[] stringTypeArray(final int size) {
      final Type[] types = new Type[size];
      Arrays.fill(types, Types.STRING);
      return types;
    }
  }
}
