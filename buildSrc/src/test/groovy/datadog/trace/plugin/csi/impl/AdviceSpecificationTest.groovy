package datadog.trace.plugin.csi.impl

import datadog.trace.plugin.csi.ValidationContext
import datadog.trace.plugin.csi.impl.CallSiteSpecification.BeforeSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AroundSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AfterSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ThisSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ReturnSpecification
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ParameterSpecification
import datadog.trace.plugin.csi.samples.AroundAdvice
import datadog.trace.plugin.csi.util.ErrorCode
import datadog.trace.plugin.csi.util.MethodType
import org.objectweb.asm.Type
import spock.lang.Specification

import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.security.MessageDigest

import datadog.trace.plugin.csi.HasErrors.Failure
import static datadog.trace.plugin.csi.impl.CallSiteFactory.typeResolver
import static datadog.trace.plugin.csi.util.CallSiteConstants.TYPE_RESOLVER

class AdviceSpecificationTest extends Specification {

  def 'advice class should be on the classpath'() {
    setup:
    final context = mockValidationContext()
    final spec = new AroundSpecification(replaceAllAdvice() { owner, name, methodType ->
      return new MethodType(type, name, methodType)
    }, replaceAllParameters(), replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError({
      Failure error -> error.errorCode == ErrorCode.UNRESOLVED_TYPE
    })

    where:
    type                             || errors
    Type.getType('Lfoo/bar/FooBar;') || 1
    Type.getType(String)             || 0
  }

  def 'before advice should return void'() {
    setup:
    final context = mockValidationContext()
    final spec = new BeforeSpecification(replaceAllAdvice() { owner, name, methodType ->
      return new MethodType(owner, name, Type.getMethodType(returnType, methodType.argumentTypes))
    }, replaceAllParameters(), replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_BEFORE_SHOULD_RETURN_VOID, _)

    where:
    returnType           || errors
    Type.getType(String) || 1
    Type.VOID_TYPE       || 0
  }

  def 'around advice should return type compatible with pointcut'() {
    setup:
    final context = mockValidationContext()
    final spec = new AroundSpecification(replaceAllAdvice() { owner, name, methodType ->
      return new MethodType(owner, name, Type.getMethodType(returnType, methodType.argumentTypes))
    }, replaceAllParameters(), replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE, _)

    where:
    returnType                  || errors
    Type.getType(MessageDigest) || 1
    Type.getType(Object)        || 0
    Type.getType(String)        || 0
  }

  def 'after advice should return type compatible with pointcut'() {
    setup:
    final context = mockValidationContext()
    final spec = new AfterSpecification(replaceAllAdvice() { owner, name, methodType ->
      return new MethodType(owner, name, Type.getMethodType(returnType, methodType.argumentTypes))
    }, replaceAllParameters(), replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE, _)

    where:
    returnType                    || errors
    Type.getType(MessageDigest) || 1
    Type.getType(Object)        || 0
    Type.getType(String)        || 0
  }

  def 'this parameter should always be the first'() {
    setup:
    final context = mockValidationContext()
    final spec = new AroundSpecification(replaceAllAdvice(), params, replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_PARAMETER_THIS_SHOULD_BE_FIRST, _)

    where:
    params                                         || errors
    [0: thisSpec(), 1: argumentSpec(0), 2: argumentSpec(1)] || 0
    [0: argumentSpec(0), 1: argumentSpec(1), 2: thisSpec()] || 1
  }

  def 'this parameter should be compatible with pointcut'() {
    setup:
    final context = mockValidationContext()
    final spec = new AroundSpecification(replaceAllAdvice() {owner, name, methodType ->
      final Type[] arguments = methodType.argumentTypes
      arguments[0] = type
      return new MethodType(owner, name, Type.getMethodType(methodType.returnType, arguments))
    }, replaceAllParameters(), replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_PARAMETER_NOT_COMPATIBLE, _)

    where:
    type                        || errors
    Type.getType(MessageDigest) || 1
    Type.getType(Object)        || 0
    Type.getType(String)        || 0
  }

  def 'return parameter should always be the last'() {
    setup:
    final context = mockValidationContext()
    final spec = new AroundSpecification(replaceAllAdvice(), params, replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_PARAMETER_RETURN_SHOULD_BE_LAST, _)

    where:
    params                                           || errors
    [0: argumentSpec(0), 1: argumentSpec(1), 2: returnSpec()] || 0
    [0: returnSpec(), 1: argumentSpec(0), 2: argumentSpec(1)] || 1
  }

  def 'return parameter should be compatible with pointcut'() {
    setup:
    final context = mockValidationContext()
    final spec = new AfterSpecification(replaceAllAdvice() {owner, name, methodType ->
      final Type[] arguments = methodType.argumentTypes
      arguments[2] = type
      return new MethodType(owner, name, Type.getMethodType(methodType.returnType, arguments))
    }, [0: argumentSpec(0), 1: argumentSpec(1), 2: returnSpec()], replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_PARAMETER_NOT_COMPATIBLE, _)

    where:
    type                          || errors
    Type.getType(MessageDigest)   || 1
    Type.getType(String)          || 0
    Type.getType(Object)          || 0
  }

  def 'argument parameters should be in order'() {
    setup:
    final context = mockValidationContext()
    final spec = new AfterSpecification(replaceAllAdvice(), parameters, replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_PARAMETER_ARGUMENT_SHOULD_BE_IN_ORDER, _ as Object[])

    where:
    parameters                                     || errors
    [0: thisSpec()]                                   || 0
    [0: thisSpec(), 1: argumentSpec(0)]                  || 0
    [0: thisSpec(), 1: argumentSpec(0), 2: argumentSpec(1)] || 0
    [0: thisSpec(), 1: argumentSpec(1), 2: argumentSpec(0)] || 1
  }

  def 'argument parameter should be compatible with pointcut'() {
    setup:
    final context = mockValidationContext()
    final spec = new AfterSpecification(replaceAllAdvice() { owner, name, methodType ->
      final Type[] arguments = methodType.argumentTypes
      arguments[2] = parameterType
      return new MethodType(owner, name, Type.getMethodType(methodType.returnType, arguments))
    }, replaceAllParameters(), replaceAllSignature())
    spec.pointcut = replaceAllPointcut()

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_PARAMETER_NOT_COMPATIBLE, _)

    where:
    parameterType                 || errors
    Type.getType(MessageDigest)   || 1
    Type.getType(String)          || 0
    Type.getType(Object)          || 0
  }

  private def mockValidationContext() {
    return Mock(ValidationContext) {
      mock ->
        mock.getContextProperty(TYPE_RESOLVER) >> typeResolver()
    }
  }

  private static MethodType methodSpecification(final Executable method, final MethodTypeBuilder builder = MethodType::new) {
    final owner = Type.getType(method.getDeclaringClass())
    final methodName = method instanceof Constructor ? '<init>' : method.name
    final parameters = method.parameterTypes.collect {Type.getType(it)}.toArray() as Type[]
    final returnType = method instanceof Constructor ? Type.VOID_TYPE : Type.getType((method as Method).getReturnType())
    final methodType = Type.getMethodType(returnType, parameters)
    return builder.call(owner, methodName, methodType)
  }

  private static MethodType replaceAllAdvice(final MethodTypeBuilder builder = MethodType::new) {
    return methodSpecification(AroundAdvice.getDeclaredMethod('aroundStringReplaceAll', String, String, String), builder)
  }

  private static MethodType replaceAllPointcut(final MethodTypeBuilder builder = MethodType::new) {
    return methodSpecification(String.getDeclaredMethod('replaceAll', String, String), builder)
  }

  private static Map<Integer, ParameterSpecification> replaceAllParameters() {
    return [0: thisSpec(), 1: argumentSpec(0), 2: argumentSpec(1)]
  }

  private static String replaceAllSignature() {
    return 'java.lang.String java.lang.String.replaceAll(java.lang.String, java.lang.String)'
  }

  private static ArgumentSpecification argumentSpec(final int index) {
    return new ArgumentSpecification(index: index)
  }

  private static ThisSpecification thisSpec() {
    return new ThisSpecification()
  }

  private static ReturnSpecification returnSpec() {
    return new ReturnSpecification()
  }

  private interface MethodTypeBuilder {
    MethodType build(Type owner, String name, Type methodType)
  }
}
