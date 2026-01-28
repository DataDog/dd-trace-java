package datadog.trace.plugin.csi.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.plugin.csi.HasErrors.Failure;
import datadog.trace.plugin.csi.ValidationContext;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.InvokeDynamicConstantsSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ParameterSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ReturnSpecification;
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ThisSpecification;
import datadog.trace.plugin.csi.util.ErrorCode;
import datadog.trace.plugin.csi.util.MethodType;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.servlet.ServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.Type;

class AdviceSpecificationTest extends BaseCsiPluginTest {

  @CallSite(spi = CallSites.class)
  static class EmptyAdvice {}

  @Test
  void testClassGeneratorErrorCallSiteWithoutAdvices() {
    ValidationContext context = mockValidationContext();
    CallSiteSpecification spec = buildClassSpecification(EmptyAdvice.class);

    spec.validate(context);
    verify(context).addError(eq(ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS), any());
  }

  @CallSite(spi = CallSites.class)
  static class NonPublicStaticMethodAdvice {
    @CallSite.Before("void java.lang.Runnable.run()")
    private void advice(@CallSite.This Runnable run) {}
  }

  @Test
  void testClassGeneratorErrorNonPublicStaticMethod() {
    ValidationContext context = mockValidationContext();
    CallSiteSpecification spec = buildClassSpecification(NonPublicStaticMethodAdvice.class);

    spec.getAdvices().forEach(it -> it.validate(context));

    verify(context).addError(eq(ErrorCode.ADVICE_METHOD_NOT_STATIC_AND_PUBLIC), any());
  }

  static class BeforeStringConcat {
    static void concat(String self, String value) {}
  }

  static Stream<Arguments> adviceClassShouldBeOnClasspathProvider() {
    return Stream.of(
        Arguments.of(Type.getType("Lfoo/bar/FooBar;"), 1),
        Arguments.of(Type.getType(BeforeStringConcat.class), 0));
  }

  @ParameterizedTest
  @MethodSource("adviceClassShouldBeOnClasspathProvider")
  void testAdviceClassShouldBeOnTheClasspath(Type type, int errors) throws Exception {
    ValidationContext context = mockValidationContext();
    BeforeSpecification spec =
        createBeforeSpec(
            BeforeStringConcat.class.getDeclaredMethod("concat", String.class, String.class),
            type,
            Arrays.asList(new ThisSpecification(), new ArgumentSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(
            argThat((Failure failure) -> failure.getErrorCode() == ErrorCode.UNRESOLVED_TYPE));
  }

  static Stream<Arguments> beforeAdviceShouldReturnVoidProvider() {
    return Stream.of(Arguments.of(String.class, 1), Arguments.of(void.class, 0));
  }

  @ParameterizedTest
  @MethodSource("beforeAdviceShouldReturnVoidProvider")
  void testBeforeAdviceShouldReturnVoid(Class<?> returnType, int errors) {
    ValidationContext context = mockValidationContext();
    BeforeSpecification spec =
        createBeforeSpec(
            BeforeStringConcat.class,
            "concat",
            returnType,
            new Class<?>[] {String.class, String.class},
            Arrays.asList(new ThisSpecification(), new ArgumentSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors)).addError(eq(ErrorCode.ADVICE_BEFORE_SHOULD_RETURN_VOID), any());
  }

  static class AroundStringConcat {
    static String concat(String self, String value) {
      return self.concat(value);
    }
  }

  static Stream<Arguments> aroundAdviceReturnTypeProvider() {
    return Stream.of(
        Arguments.of(MessageDigest.class, 1),
        Arguments.of(Object.class, 0),
        Arguments.of(String.class, 0));
  }

  @ParameterizedTest
  @MethodSource("aroundAdviceReturnTypeProvider")
  void testAroundAdviceShouldReturnTypeCompatibleWithPointcut(Class<?> returnType, int errors) {
    ValidationContext context = mockValidationContext();
    AroundSpecification spec =
        createAroundSpec(
            AroundStringConcat.class,
            "concat",
            returnType,
            new Class<?>[] {String.class, String.class},
            Arrays.asList(new ThisSpecification(), new ArgumentSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(eq(ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE), any());
  }

  static class AfterStringConcat {
    static String concat(String self, String value, String result) {
      return result;
    }
  }

  static Stream<Arguments> afterAdviceReturnTypeProvider() {
    return Stream.of(
        Arguments.of(MessageDigest.class, 1),
        Arguments.of(Object.class, 0),
        Arguments.of(String.class, 0));
  }

  @ParameterizedTest
  @MethodSource("afterAdviceReturnTypeProvider")
  void testAfterAdviceShouldReturnTypeCompatibleWithPointcut(Class<?> returnType, int errors) {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            AfterStringConcat.class,
            "concat",
            returnType,
            new Class<?>[] {String.class, String.class, String.class},
            Arrays.asList(
                new ThisSpecification(), new ArgumentSpecification(), new ReturnSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(eq(ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE), any());
  }

  static Stream<Arguments> thisParameterShouldBeFirstProvider() {
    return Stream.of(
        Arguments.of(Arrays.asList(new ThisSpecification(), new ArgumentSpecification()), 0),
        Arguments.of(Arrays.asList(new ArgumentSpecification(), new ThisSpecification()), 1));
  }

  @ParameterizedTest
  @MethodSource("thisParameterShouldBeFirstProvider")
  void testThisParameterShouldAlwaysBeTheFirst(List<ParameterSpecification> params, int errors)
      throws Exception {
    ValidationContext context = mockValidationContext();
    AroundSpecification spec =
        createAroundSpec(
            AroundStringConcat.class.getDeclaredMethod("concat", String.class, String.class),
            params,
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(eq(ErrorCode.ADVICE_PARAMETER_THIS_SHOULD_BE_FIRST), any());
  }

  static Stream<Arguments> thisParameterCompatibilityProvider() {
    return Stream.of(
        Arguments.of(MessageDigest.class, 1),
        Arguments.of(Object.class, 0),
        Arguments.of(String.class, 0));
  }

  @ParameterizedTest
  @MethodSource("thisParameterCompatibilityProvider")
  void testThisParameterShouldBeCompatibleWithPointcut(Class<?> type, int errors) {
    ValidationContext context = mockValidationContext();
    AroundSpecification spec =
        createAroundSpec(
            AroundStringConcat.class,
            "concat",
            String.class,
            new Class<?>[] {type, String.class},
            Arrays.asList(new ThisSpecification(), new ArgumentSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(eq(ErrorCode.ADVICE_METHOD_PARAM_THIS_NOT_COMPATIBLE), any());
    if (type != String.class) {
      verify(context)
          .addError(
              argThat((Failure failure) -> failure.getErrorCode() == ErrorCode.UNRESOLVED_METHOD));
    }
  }

  static Stream<Arguments> returnParameterShouldBeLastProvider() {
    return Stream.of(
        Arguments.of(
            Arrays.asList(
                new ThisSpecification(), new ArgumentSpecification(), new ReturnSpecification()),
            0),
        Arguments.of(
            Arrays.asList(
                new ThisSpecification(), new ReturnSpecification(), new ArgumentSpecification()),
            1));
  }

  @ParameterizedTest
  @MethodSource("returnParameterShouldBeLastProvider")
  void testReturnParameterShouldAlwaysBeTheLast(List<ParameterSpecification> params, int errors)
      throws Exception {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            AfterStringConcat.class.getDeclaredMethod(
                "concat", String.class, String.class, String.class),
            params,
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(eq(ErrorCode.ADVICE_PARAMETER_RETURN_SHOULD_BE_LAST), any());
  }

  static Stream<Arguments> returnParameterCompatibilityProvider() {
    return Stream.of(
        Arguments.of(MessageDigest.class, 1),
        Arguments.of(String.class, 0),
        Arguments.of(Object.class, 0));
  }

  @ParameterizedTest
  @MethodSource("returnParameterCompatibilityProvider")
  void testReturnParameterShouldBeCompatibleWithPointcut(Class<?> returnType, int errors) {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            AfterStringConcat.class,
            "concat",
            String.class,
            new Class<?>[] {String.class, String.class, returnType},
            Arrays.asList(
                new ThisSpecification(), new ArgumentSpecification(), new ReturnSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(eq(ErrorCode.ADVICE_METHOD_PARAM_RETURN_NOT_COMPATIBLE), any());
    if (returnType != String.class) {
      verify(context)
          .addError(
              argThat((Failure failure) -> failure.getErrorCode() == ErrorCode.UNRESOLVED_METHOD));
    }
  }

  static Stream<Arguments> argumentParameterCompatibilityProvider() {
    return Stream.of(
        Arguments.of(MessageDigest.class, 1),
        Arguments.of(String.class, 0),
        Arguments.of(Object.class, 0));
  }

  @ParameterizedTest
  @MethodSource("argumentParameterCompatibilityProvider")
  void testArgumentParameterShouldBeCompatibleWithPointcut(Class<?> parameterType, int errors) {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            AfterStringConcat.class,
            "concat",
            String.class,
            new Class<?>[] {String.class, parameterType, String.class},
            Arrays.asList(
                new ThisSpecification(), new ArgumentSpecification(), new ReturnSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context, times(errors))
        .addError(eq(ErrorCode.ADVICE_METHOD_PARAM_NOT_COMPATIBLE), any());
    if (parameterType != String.class) {
      verify(context)
          .addError(
              argThat((Failure failure) -> failure.getErrorCode() == ErrorCode.UNRESOLVED_METHOD));
    }
  }

  static class BadAfterStringConcat {
    static String concat(String param1, String param2) {
      return param2;
    }
  }

  static Stream<Arguments> afterAdviceRequiresThisAndReturnProvider() {
    return Stream.of(
        Arguments.of(
            Arrays.asList(new ArgumentSpecification(), new ReturnSpecification()),
            ErrorCode.ADVICE_AFTER_SHOULD_HAVE_THIS),
        Arguments.of(
            Arrays.asList(new ThisSpecification(), new ArgumentSpecification()),
            ErrorCode.ADVICE_AFTER_SHOULD_HAVE_RETURN));
  }

  @ParameterizedTest
  @MethodSource("afterAdviceRequiresThisAndReturnProvider")
  void testAfterAdviceRequiresThisAndReturnParameters(
      List<ParameterSpecification> params, ErrorCode error) throws Exception {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            BadAfterStringConcat.class.getDeclaredMethod("concat", String.class, String.class),
            params,
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context).addError(eq(error), any());
  }

  static class BadAllArgsAfterStringConcat {
    static String concat(Object[] param1, String param2, String param3) {
      return param3;
    }
  }

  @Test
  void shouldNotMixAllArgumentsAndArgument() throws Exception {
    ValidationContext context = mockValidationContext();
    AllArgsSpecification allArgs = new AllArgsSpecification();
    allArgs.setIncludeThis(true);
    AfterSpecification spec =
        createAfterSpec(
            BadAllArgsAfterStringConcat.class.getDeclaredMethod(
                "concat", Object[].class, String.class, String.class),
            Arrays.asList(allArgs, new ArgumentSpecification(), new ReturnSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context).addError(eq(ErrorCode.ADVICE_PARAMETER_ALL_ARGS_MIXED), any());
    verify(context).addError(eq(ErrorCode.ADVICE_PARAMETER_ARGUMENT_OUT_OF_BOUNDS), any());
  }

  static class TestInheritedMethod {
    static String after(ServletRequest request, String parameter, String value) {
      return value;
    }
  }

  @Test
  void testInheritedMethods() throws Exception {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            TestInheritedMethod.class.getDeclaredMethod(
                "after", ServletRequest.class, String.class, String.class),
            Arrays.asList(
                new ThisSpecification(), new ArgumentSpecification(), new ReturnSpecification()),
            "java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)");

    spec.validate(context);
  }

  static class TestInvokeDynamicConstants {
    static Object after(Object[] parameter, Object result, Object[] constants) {
      return result;
    }
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicConstants() throws Exception {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            TestInvokeDynamicConstants.class.getDeclaredMethod(
                "after", Object[].class, Object.class, Object[].class),
            Arrays.asList(
                new AllArgsSpecification(),
                new ReturnSpecification(),
                new InvokeDynamicConstantsSpecification()),
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
            true);

    spec.validate(context);
  }

  static Stream<Arguments> invokeDynamicConstantsShouldBeLastProvider() {
    return Stream.of(
        Arguments.of(
            Arrays.asList(
                new AllArgsSpecification(),
                new ReturnSpecification(),
                new InvokeDynamicConstantsSpecification()),
            null),
        Arguments.of(
            Arrays.asList(
                new AllArgsSpecification(),
                new InvokeDynamicConstantsSpecification(),
                new ReturnSpecification()),
            ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_SHOULD_BE_LAST));
  }

  @ParameterizedTest
  @MethodSource("invokeDynamicConstantsShouldBeLastProvider")
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicConstantsShouldBeLast(List<ParameterSpecification> params, ErrorCode error)
      throws Exception {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            TestInvokeDynamicConstants.class.getDeclaredMethod(
                "after", Object[].class, Object.class, Object[].class),
            params,
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
            true);

    spec.validate(context);
    if (error != null) {
      verify(context).addError(eq(error), any());
    }
  }

  static class TestInvokeDynamicConstantsNonInvokeDynamic {
    static Object after(Object self, Object[] parameter, Object value, Object[] constants) {
      return value;
    }
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicConstantsOnNonInvokeDynamicPointcut() throws Exception {
    ValidationContext context = mockValidationContext();
    AfterSpecification spec =
        createAfterSpec(
            TestInvokeDynamicConstantsNonInvokeDynamic.class.getDeclaredMethod(
                "after", Object.class, Object[].class, Object.class, Object[].class),
            Arrays.asList(
                new ThisSpecification(),
                new AllArgsSpecification(),
                new InvokeDynamicConstantsSpecification(),
                new ReturnSpecification()),
            "java.lang.String java.lang.String.concat(java.lang.String)");

    spec.validate(context);
    verify(context)
        .addError(
            eq(ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_ON_NON_INVOKE_DYNAMIC), any());
  }

  static class TestInvokeDynamicConstantsBefore {
    static void before(Object[] parameter, Object[] constants) {}
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicConstantsOnNonAfterAdvice() throws Exception {
    ValidationContext context = mockValidationContext();
    BeforeSpecification spec =
        createBeforeSpec(
            TestInvokeDynamicConstantsBefore.class.getDeclaredMethod(
                "before", Object[].class, Object[].class),
            Arrays.asList(new AllArgsSpecification(), new InvokeDynamicConstantsSpecification()),
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
            true);

    spec.validate(context);
    verify(context)
        .addError(eq(ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_NON_AFTER_ADVICE), any());
  }

  static class TestInvokeDynamicConstantsAround {
    static java.lang.invoke.CallSite around(
        MethodHandles.Lookup lookup,
        String name,
        java.lang.invoke.MethodType concatType,
        String recipe,
        Object... constants) {
      return null;
    }
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_9)
  void testInvokeDynamicOnAroundAdvice() throws Exception {
    ValidationContext context = mockValidationContext();
    AroundSpecification spec =
        createAroundSpec(
            TestInvokeDynamicConstantsAround.class.getDeclaredMethod(
                "around",
                MethodHandles.Lookup.class,
                String.class,
                java.lang.invoke.MethodType.class,
                String.class,
                Object[].class),
            Arrays.asList(
                new ArgumentSpecification(),
                new ArgumentSpecification(),
                new ArgumentSpecification(),
                new ArgumentSpecification(),
                new ArgumentSpecification()),
            "java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])",
            true);

    spec.validate(context);
  }

  @CallSite(spi = CallSites.class)
  static class AfterWithVoidWrongAdvice {
    @CallSite.After("void java.lang.String.getChars(int, int, char[], int)")
    static String after(@CallSite.AllArguments Object[] args, @CallSite.Return String result) {
      return result;
    }
  }

  @Test
  void testAfterAdviceWithVoidShouldNotUseReturn() {
    ValidationContext context = mockValidationContext();
    CallSiteSpecification spec = buildClassSpecification(AfterWithVoidWrongAdvice.class);

    spec.getAdvices().forEach(it -> it.validate(context));

    verify(context).addError(eq(ErrorCode.ADVICE_AFTER_VOID_METHOD_SHOULD_RETURN_VOID), any());
    verify(context).addError(eq(ErrorCode.ADVICE_AFTER_VOID_METHOD_SHOULD_NOT_HAVE_RETURN), any());
  }

  // Helper methods to create specifications
  private BeforeSpecification createBeforeSpec(
      Method method, List<ParameterSpecification> params, String signature) {
    return createBeforeSpec(method, null, params, signature, false);
  }

  private BeforeSpecification createBeforeSpec(
      Method method, List<ParameterSpecification> params, String signature, boolean invokeDynamic) {
    return createBeforeSpec(method, null, params, signature, invokeDynamic);
  }

  private BeforeSpecification createBeforeSpec(
      Method method, Type ownerOverride, List<ParameterSpecification> params, String signature) {
    return createBeforeSpec(method, ownerOverride, params, signature, false);
  }

  private BeforeSpecification createBeforeSpec(
      Method method,
      Type ownerOverride,
      List<ParameterSpecification> params,
      String signature,
      boolean invokeDynamic) {
    Type owner = ownerOverride != null ? ownerOverride : Type.getType(method.getDeclaringClass());
    Type[] argTypes =
        Arrays.stream(method.getParameterTypes()).map(Type::getType).toArray(Type[]::new);
    Type returnType = Type.getType(method.getReturnType());
    MethodType methodType =
        new MethodType(owner, method.getName(), Type.getMethodType(returnType, argTypes));
    Map<Integer, ParameterSpecification> paramMap = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      paramMap.put(i, params.get(i));
    }
    updateArgumentIndices(paramMap);
    BeforeSpecification spec =
        new BeforeSpecification(methodType, paramMap, signature, invokeDynamic);
    spec.parseSignature(CallSiteFactory.pointcutParser());
    return spec;
  }

  private BeforeSpecification createBeforeSpec(
      Class<?> clazz,
      String methodName,
      Class<?> returnType,
      Class<?>[] argTypes,
      List<ParameterSpecification> params,
      String signature) {
    Type owner = Type.getType(clazz);
    Type[] argTypesAsm = Arrays.stream(argTypes).map(Type::getType).toArray(Type[]::new);
    Type returnTypeAsm = Type.getType(returnType);
    MethodType methodType =
        new MethodType(owner, methodName, Type.getMethodType(returnTypeAsm, argTypesAsm));
    Map<Integer, ParameterSpecification> paramMap = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      paramMap.put(i, params.get(i));
    }
    updateArgumentIndices(paramMap);
    BeforeSpecification spec = new BeforeSpecification(methodType, paramMap, signature, false);
    spec.parseSignature(CallSiteFactory.pointcutParser());
    return spec;
  }

  private AroundSpecification createAroundSpec(
      Method method, List<ParameterSpecification> params, String signature) {
    return createAroundSpec(method, params, signature, false);
  }

  private AroundSpecification createAroundSpec(
      Method method, List<ParameterSpecification> params, String signature, boolean invokeDynamic) {
    Type owner = Type.getType(method.getDeclaringClass());
    Type[] argTypes =
        Arrays.stream(method.getParameterTypes()).map(Type::getType).toArray(Type[]::new);
    Type returnType = Type.getType(method.getReturnType());
    MethodType methodType =
        new MethodType(owner, method.getName(), Type.getMethodType(returnType, argTypes));
    Map<Integer, ParameterSpecification> paramMap = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      paramMap.put(i, params.get(i));
    }
    updateArgumentIndices(paramMap);
    AroundSpecification spec =
        new AroundSpecification(methodType, paramMap, signature, invokeDynamic);
    spec.parseSignature(CallSiteFactory.pointcutParser());
    return spec;
  }

  private AroundSpecification createAroundSpec(
      Class<?> clazz,
      String methodName,
      Class<?> returnType,
      Class<?>[] argTypes,
      List<ParameterSpecification> params,
      String signature) {
    Type owner = Type.getType(clazz);
    Type[] argTypesAsm = Arrays.stream(argTypes).map(Type::getType).toArray(Type[]::new);
    Type returnTypeAsm = Type.getType(returnType);
    MethodType methodType =
        new MethodType(owner, methodName, Type.getMethodType(returnTypeAsm, argTypesAsm));
    Map<Integer, ParameterSpecification> paramMap = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      paramMap.put(i, params.get(i));
    }
    updateArgumentIndices(paramMap);
    AroundSpecification spec = new AroundSpecification(methodType, paramMap, signature, false);
    spec.parseSignature(CallSiteFactory.pointcutParser());
    return spec;
  }

  private AfterSpecification createAfterSpec(
      Method method, List<ParameterSpecification> params, String signature) {
    return createAfterSpec(method, params, signature, false);
  }

  private AfterSpecification createAfterSpec(
      Method method, List<ParameterSpecification> params, String signature, boolean invokeDynamic) {
    Type owner = Type.getType(method.getDeclaringClass());
    Type[] argTypes =
        Arrays.stream(method.getParameterTypes()).map(Type::getType).toArray(Type[]::new);
    Type returnType = Type.getType(method.getReturnType());
    MethodType methodType =
        new MethodType(owner, method.getName(), Type.getMethodType(returnType, argTypes));
    Map<Integer, ParameterSpecification> paramMap = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      paramMap.put(i, params.get(i));
    }
    updateArgumentIndices(paramMap);
    AfterSpecification spec =
        new AfterSpecification(methodType, paramMap, signature, invokeDynamic);
    spec.parseSignature(CallSiteFactory.pointcutParser());
    return spec;
  }

  private AfterSpecification createAfterSpec(
      Class<?> clazz,
      String methodName,
      Class<?> returnType,
      Class<?>[] argTypes,
      List<ParameterSpecification> params,
      String signature) {
    Type owner = Type.getType(clazz);
    Type[] argTypesAsm = Arrays.stream(argTypes).map(Type::getType).toArray(Type[]::new);
    Type returnTypeAsm = Type.getType(returnType);
    MethodType methodType =
        new MethodType(owner, methodName, Type.getMethodType(returnTypeAsm, argTypesAsm));
    Map<Integer, ParameterSpecification> paramMap = new HashMap<>();
    for (int i = 0; i < params.size(); i++) {
      paramMap.put(i, params.get(i));
    }
    updateArgumentIndices(paramMap);
    AfterSpecification spec = new AfterSpecification(methodType, paramMap, signature, false);
    spec.parseSignature(CallSiteFactory.pointcutParser());
    return spec;
  }

  private void updateArgumentIndices(Map<Integer, ParameterSpecification> paramMap) {
    int index = 0;
    for (ParameterSpecification param : paramMap.values()) {
      if (param instanceof ArgumentSpecification) {
        ((ArgumentSpecification) param).setIndex(index++);
      }
    }
  }
}
