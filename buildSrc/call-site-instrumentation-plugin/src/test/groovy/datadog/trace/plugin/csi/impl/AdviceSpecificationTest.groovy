package datadog.trace.plugin.csi.impl

import datadog.trace.agent.tooling.csi.CallSite
import datadog.trace.plugin.csi.HasErrors.Failure
import datadog.trace.plugin.csi.util.ErrorCode
import org.objectweb.asm.Type

import datadog.trace.plugin.csi.impl.CallSiteSpecification.ThisSpecification as This
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ReturnSpecification as Return
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ArgumentSpecification as Arg
import datadog.trace.plugin.csi.impl.CallSiteSpecification.AllArgsSpecification as AllArgs
import datadog.trace.plugin.csi.impl.CallSiteSpecification.ParameterSpecification

import javax.servlet.ServletRequest
import javax.servlet.http.HttpServletRequest
import java.security.MessageDigest

class AdviceSpecificationTest extends BaseCsiPluginTest {

  @CallSite
  class EmptyAdvice {}

  def 'test class generator error, call site without advices'() {
    setup:
    final context = mockValidationContext()
    final spec = buildClassSpecification(EmptyAdvice)

    when:
    spec.validate(context)

    then:
    1 * context.addError(ErrorCode.CALL_SITE_SHOULD_HAVE_ADVICE_METHODS, _)
  }

  @CallSite
  class NonPublicStaticMethodAdvice {
    @CallSite.Before("void java.lang.Runnable.run()")
    private void advice(@CallSite.This final Runnable run) {}
  }

  def 'test class generator error, non public static method'() {
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

  def 'advice class should be on the classpath'(final Type type, final int errors) {
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
    errors * context.addError({ Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_TYPE })
    0 * context.addError(*_)

    where:
    type                             || errors
    Type.getType('Lfoo/bar/FooBar;') || 1
    Type.getType(BeforeStringConcat) || 0
  }

  def 'before advice should return void'(final Class<?> returnType, final int errors) {
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

  def 'around advice should return type compatible with pointcut'(final Class<?> returnType, final int errors) {
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
    returnType    || errors
    MessageDigest || 1
    Object        || 0
    String        || 0
  }

  class AfterStringConcat {
    static String concat(final String self, final String value, final String result) {
      return result
    }
  }

  def 'after advice should return type compatible with pointcut'(final Class<?> returnType, final int errors) {
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
    returnType    || errors
    MessageDigest || 1
    Object        || 0
    String        || 0
  }

  def 'this parameter should always be the first'(final List<ParameterSpecification> params, final int errors) {
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
    params                  || errors
    [new This(), new Arg()] || 0
    [new Arg(), new This()] || 1
  }


  def 'this parameter should be compatible with pointcut'(final Class<?> type, final int errors) {
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
      1 * context.addError({ Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_METHOD })
    }
    0 * context.addError(*_)

    where:
    type          || errors
    MessageDigest || 1
    Object        || 0
    String        || 0
  }

  def 'return parameter should always be the last'(final List<ParameterSpecification> params, final int errors) {
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
    params                                || errors
    [new This(), new Arg(), new Return()] || 0
    [new This(), new Return(), new Arg()] || 1
  }


  def 'return parameter should be compatible with pointcut'(final Class<?> returnType, final int errors) {
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
      1 * context.addError({ Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_METHOD })
    }
    0 * context.addError(*_)

    where:
    returnType    || errors
    MessageDigest || 1
    String        || 0
    Object        || 0
  }


  def 'argument parameter should be compatible with pointcut'(final Class<?> parameterType, final int errors) {
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
      1 * context.addError({ Failure failure -> failure.errorCode == ErrorCode.UNRESOLVED_METHOD })
    }
    0 * context.addError(*_)

    where:
    parameterType || errors
    MessageDigest || 1
    String        || 0
    Object        || 0
  }

  class BadAfterStringConcat {
    static String concat(final String param1, final String param2) {
      return param2
    }
  }

  def 'after advice requires @This and @Return parameters'(final List<ParameterSpecification> params, final ErrorCode error) {
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
    params                    || error
    [new Arg(), new Return()] || ErrorCode.ADVICE_AFTER_SHOULD_HAVE_THIS
    [new This(), new Arg()]   || ErrorCode.ADVICE_AFTER_SHOULD_HAVE_RETURN
  }

  class BadAllArgsAfterStringConcat {
    static String concat(final Object[] param1, final String param2, final String param3) {
      return param3
    }
  }

  def 'should not mix @AllArguments and @Argument'() {
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

  def 'test inherited methods'() {
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
}
