package datadog.trace.plugin.csi.impl

import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.agent.tooling.csi.CallSites
import datadog.trace.plugin.csi.HasErrors.Failure
import datadog.trace.plugin.csi.util.ErrorCode
import groovy.transform.CompileDynamic
import org.objectweb.asm.Type

import datadog.trace.plugin.csi.impl.CallSiteSpecification.ThisSpecification as This
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ReturnSpecification as Return
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification as Arg
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification as AllArgs
import datadog.trace.plugin.csi.impl.CallSiteSpecification.InvokeDynamicConstantsSpecification as DynConsts
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ParameterSpecification
import spock.lang.Requires

import javax.servlet.ServletRequest
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.security.MessageDigest

@CompileDynamic
class AdviceSpecificationTest extends BaseCsiPluginTest {

  @CallSite(spi = CallSites)
  class EmptyAdvice {}

  void 'test class generator error, call site without advices'() {
    setup:
    final context = mockValidationContext()
    final spec = buildClassSpecification(EmptyAdvice)

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS, _)
  }

  @CallSite(spi = CallSites)
  class NonPublicStaticMethodAdvice {
    @CallSite.Before("void java.lang.Runnable.run()")
    private void advice(@CallSite.This final Runnable run) {}
  }

  void 'test class generator error, non public static method'() {
    setup:
    final context = mockValidationContext()
    final spec = buildClassSpecification(NonPublicStaticMethodAdvice)

    when:
    spec.advices.each { it.validate(context) }

    then:
    1 * context.addError(ErrorCode.ADVICE_METHOD_NOT_STATIC_AND_PUBLIC, _)
  }

  class BeforeStringConcat {
    static void concat(final String self, final String value) {}
  }

  void 'test advice class should be on the classpath'(final Type type, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = before {
      advice {
        method(BeforeStringConcat.getDeclaredMethod('concat', String, String))
        owner(type) // override owner
      }
      parameters(new This(), new Arg())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError { Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_TYPE }
    0 * context.addError(*_)

    where:
    type                             | errors
    Type.getType('Lfoo/bar/FooBar;') | 1
    Type.getType(BeforeStringConcat) | 0
  }

  void 'test before advice should return void'(final Class<?> returnType, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = before {
      advice {
        owner(BeforeStringConcat)
        method('concat')
        descriptor(returnType, String, String) // change return
      }
      parameters(new This(), new Arg())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_BEFORE_SHOULD_RETURN_VOID, _)
    0 * context.addError(*_)


    where:
    returnType || errors
    String     || 1
    void.class || 0
  }

  class AroundStringConcat {
    static String concat(final String self, final String value) {
      return self.concat(value)
    }
  }

  void 'test around advice should return type compatible with pointcut'(final Class<?> returnType, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = around {
      advice {
        owner(AroundStringConcat)
        method('concat')
        descriptor(returnType, String, String) // change return
      }
      parameters(new This(), new Arg())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE, _)
    0 * context.addError(*_)

    where:
    returnType    | errors
    MessageDigest | 1
    Object        | 0
    String        | 0
  }

  class AfterStringConcat {
    static String concat(final String self, final String value, final String result) {
      return result
    }
  }

  void 'test after advice should return type compatible with pointcut'(final Class<?> returnType, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        owner(AfterStringConcat)
        method('concat')
        descriptor(returnType, String, String, String)
        // change return
      }
      parameters(new This(), new Arg(), new Return())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_RETURN_NOT_COMPATIBLE, _)
    0 * context.addError(*_)

    where:
    returnType    | errors
    MessageDigest | 1
    Object        | 0
    String        | 0
  }

  void 'test this parameter should always be the first'(final List<ParameterSpecification> params, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = around {
      advice {
        method(AroundStringConcat.getDeclaredMethod('concat', String, String))
      }
      parameters(params as ParameterSpecification[])
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_PARAMETER_THIS_SHOULD_BE_FIRST, _)
    0 * context.addError(*_)

    where:
    params                  | errors
    [new This(), new Arg()] | 0
    [new Arg(), new This()] | 1
  }


  void 'test this parameter should be compatible with pointcut'(final Class<?> type, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = around {
      advice {
        owner(AroundStringConcat)
        method('concat')
        descriptor(String, type, String)
      }
      parameters(new This(), new Arg())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_PARAM_THIS_NOT_COMPATIBLE, _)
    // advice returns String so other return types won't be able to find the method
    if (type != String) {
      1 * context.addError { Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_METHOD }
    }
    0 * context.addError(*_)

    where:
    type          | errors
    MessageDigest | 1
    Object        | 0
    String        | 0
  }

  void 'test return parameter should always be the last'(final List<ParameterSpecification> params, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        method(AfterStringConcat.getDeclaredMethod('concat', String, String, String))
      }
      parameters(params as ParameterSpecification[])
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_PARAMETER_RETURN_SHOULD_BE_LAST, _)
    // other errors are ignored

    where:
    params                                | errors
    [new This(), new Arg(), new Return()] | 0
    [new This(), new Return(), new Arg()] | 1
  }


  void 'test return parameter should be compatible with pointcut'(final Class<?> returnType, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        owner(AfterStringConcat)
        method('concat')
        descriptor(String, String, String, returnType)
      }
      parameters(new This(), new Arg(), new Return())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_PARAM_RETURN_NOT_COMPATIBLE, _)
    // advice returns String so other return types won't be able to find the method
    if (returnType != String) {
      1 * context.addError { Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_METHOD }
    }
    0 * context.addError(*_)

    where:
    returnType    | errors
    MessageDigest | 1
    String        | 0
    Object        | 0
  }


  void 'test argument parameter should be compatible with pointcut'(final Class<?> parameterType, final int errors) {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        owner(AfterStringConcat)
        method('concat')
        descriptor(String, String, parameterType, String)
      }
      parameters(new This(), new Arg(), new Return())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    errors * context.addError(ErrorCode.ADVICE_METHOD_PARAM_NOT_COMPATIBLE, _)
    // advice parameter is a String so with other types won't be able to find the method
    if (parameterType != String) {
      1 * context.addError { Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_METHOD }
    }
    0 * context.addError(*_)

    where:
    parameterType | errors
    MessageDigest | 1
    String        | 0
    Object        | 0
  }

  class BadAfterStringConcat {
    static String concat(final String param1, final String param2) {
      return param2
    }
  }

  void 'test after advice requires @This and @Return parameters'(final List<ParameterSpecification> params, final ErrorCode error) {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        method(BadAfterStringConcat.getDeclaredMethod('concat', String, String))
      }
      parameters(params as ParameterSpecification[])
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    1 * context.addError(error, _)
    0 * context.addError(*_)

    where:
    params                    | error
    [new Arg(), new Return()] | ErrorCode.ADVICE_AFTER_SHOULD_HAVE_THIS
    [new This(), new Arg()]   | ErrorCode.ADVICE_AFTER_SHOULD_HAVE_RETURN
  }

  class BadAllArgsAfterStringConcat {
    static String concat(final Object[] param1, final String param2, final String param3) {
      return param3
    }
  }

  void 'should not mix @AllArguments and @Argument'() {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        method(BadAllArgsAfterStringConcat.getDeclaredMethod('concat', Object[], String, String))
      }
      parameters(new AllArgs(includeThis: true), new Arg(), new Return())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.ADVICE_PARAMETER_ALL_ARGS_MIXED, _)
    1 * context.addError(ErrorCode.ADVICE_PARAMETER_ARGUMENT_OUT_OF_BOUNDS, _) // all args consumes all arguments
    0 * context.addError(*_)
  }

  static class TestInheritedMethod {
    static String after(final ServletRequest request, final String parameter, final String value) {
      return value
    }
  }

  void 'test inherited methods'() {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        method(TestInheritedMethod.getDeclaredMethod('after', ServletRequest, String, String))
      }
      parameters(new This(), new Arg(), new Return())
      signature('java.lang.String javax.servlet.http.HttpServletRequest.getParameter(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    0 * context.addError(*_)
  }

  static class TestInvokeDynamicConstants {
    static Object after(final Object[] parameter, final Object result, final Object[] constants) {
      return result
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic constants'() {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        method(TestInvokeDynamicConstants.getDeclaredMethod('after', Object[], Object, Object[]))
      }
      parameters(new AllArgs(), new Return(), new DynConsts())
      signature('java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])')
      invokeDynamic(true)
    }

    when:
    spec.validate(context)

    then:
    0 * context.addError(*_)
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic constants should be last'(final List<ParameterSpecification> params, final ErrorCode error) {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        method(TestInvokeDynamicConstants.getDeclaredMethod('after', Object[], Object, Object[]))
      }
      parameters(params as ParameterSpecification[])
      signature('java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])')
      invokeDynamic(true)
    }

    when:
    spec.validate(context)

    then:
    if (error != null) {
      1 * context.addError(error, _)
    }
    0 * context.addError(*_)

    where:
    params                                         | error
    [new AllArgs(), new Return(), new DynConsts()] | null
    [new AllArgs(), new DynConsts(), new Return()] | ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_SHOULD_BE_LAST
  }

  static class TestInvokeDynamicConstantsNonInvokeDynamic {
    static Object after(final Object self, final Object[] parameter, final Object value, final Object[] constants) {
      return value
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic constants on non invoke dynamic pointcut'() {
    setup:
    final context = mockValidationContext()
    final spec = after {
      advice {
        method(TestInvokeDynamicConstantsNonInvokeDynamic.getDeclaredMethod('after', Object, Object[], Object, Object[]))
      }
      parameters(new This(), new AllArgs(), new DynConsts(), new Return())
      signature('java.lang.String java.lang.String.concat(java.lang.String)')
    }

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_ON_NON_INVOKE_DYNAMIC, _)
  }

  static class TestInvokeDynamicConstantsBefore {
    static void before(final Object[] parameter, final Object[] constants) {
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic constants on non @After advice'() {
    setup:
    final context = mockValidationContext()
    final spec = before {
      advice {
        method(TestInvokeDynamicConstantsBefore.getDeclaredMethod('before', Object[], Object[]))
      }
      parameters(new AllArgs(), new DynConsts())
      signature('java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])')
      invokeDynamic(true)
    }

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_NON_AFTER_ADVICE, _)
  }

  static class TestInvokeDynamicConstantsAround {
    static java.lang.invoke.CallSite around(final MethodHandles.Lookup lookup, final String name, final MethodType concatType, final String recipe, final Object... constants) {
      return null
    }
  }

  @Requires({
    jvm.java9Compatible
  })
  void 'test invoke dynamic on @Around advice'() {
    setup:
    final context = mockValidationContext()
    final spec = around {
      advice {
        method(TestInvokeDynamicConstantsAround.getDeclaredMethod('around', MethodHandles.Lookup, String, MethodType, String, Object[]))
      }
      parameters(new Arg(), new Arg(), new Arg(), new Arg(), new Arg())
      signature('java.lang.invoke.CallSite java.lang.invoke.StringConcatFactory.makeConcatWithConstants(java.lang.invoke.MethodHandles$Lookup, java.lang.String, java.lang.invoke.MethodType, java.lang.String, java.lang.Object[])')
      invokeDynamic(true)
    }

    when:
    spec.validate(context)

    then:
    0 * context.addError(_, _)
  }


  @CallSite(spi = CallSites)
  class AfterWithVoidWrongAdvice {
    @CallSite.After("void java.lang.String.getChars(int, int, char[], int)")
    static String after(@CallSite.AllArguments final Object[] args, @CallSite.Return final String result) {
      return result
    }
  }

  void 'test after advice with void should not use @Return'() {
    setup:
    final context = mockValidationContext()
    final spec = buildClassSpecification(AfterWithVoidWrongAdvice)

    when:
    spec.advices.each { it.validate(context) }

    then:
    1 * context.addError(ErrorCode.ADVICE_AFTER_VOID_METHOD_SHOULD_RETURN_VOID, _)
    1 * context.addError(ErrorCode.ADVICE_AFTER_VOID_METHOD_SHOULD_NOT_HAVE_RETURN, _)
  }
}
