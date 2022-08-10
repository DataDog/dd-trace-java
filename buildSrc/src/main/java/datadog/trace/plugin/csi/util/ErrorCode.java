package datadog.trace.plugin.csi.util;

import datadog.trace.plugin.csi.impl.CallSiteSpecification.AdviceSpecification;
import java.util.function.Function;
import org.objectweb.asm.Type;

public enum ErrorCode implements Function<Object[], String> {

  // TypeResolver

  UNRESOLVED_TYPE {
    @Override
    public String apply(final Object[] objects) {
      final Type type = (Type) objects[0];
      return String.format("Cannot resolve type '%s'", type.getClassName());
    }
  },

  UNRESOLVED_METHOD {
    @Override
    public String apply(final Object[] objects) {
      final MethodType method = (MethodType) objects[0];
      return String.format(
          "Cannot resolve method '%s' in type '%s' with descriptor '%s'",
          method.getMethodName(), method.getOwner(), method.getMethodType().getDescriptor());
    }
  },

  // Specification

  CALL_SITE_SHOULD_HAVE_ADVICE_METHODS {
    @Override
    public String apply(final Object[] objects) {
      return "@CallSite annotated class should contain advice methods (@Before, @Around or @After)";
    }
  },

  CALL_SITE_SPI_SHOULD_BE_AN_INTERFACE {
    @Override
    public String apply(final Object[] objects) {
      final Class<?> spiClass = (Class<?>) objects[0];
      return String.format("@CallSite spi class should be an interface, received '%s'", spiClass);
    }
  },

  CALL_SITE_SPI_SHOULD_BE_EMPTY {
    @Override
    public String apply(final Object[] objects) {
      final Class<?> spiClass = (Class<?>) objects[0];
      return String.format(
          "@CallSite spi class should not define any methods, spi class '%s'", spiClass);
    }
  },

  ADVICE_METHOD_NOT_STATIC_AND_PUBLIC {
    @Override
    public String apply(final Object[] objects) {
      return "Advice method should be static and public";
    }
  },

  ADVICE_METHOD_RETURN_NOT_COMPATIBLE {
    @Override
    public String apply(final Object[] objects) {
      final Type pointcutReturn = (Type) objects[0];
      final Type callsiteReturn = (Type) objects[1];
      return String.format(
          "Call site return type '%s' not compatible with pointcut return '%s'",
          callsiteReturn.getClassName(), pointcutReturn.getClassName());
    }
  },

  ADVICE_METHOD_PARAMETER_NOT_COMPATIBLE {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      final Type pointcutParameter = (Type) objects[1];
      final Type callsiteParameter = (Type) objects[2];
      return String.format(
          "Call site parameter with index '%s' type '%s' not compatible with pointcut return '%s'",
          index, callsiteParameter.getClassName(), pointcutParameter.getClassName());
    }
  },

  ADVICE_PARAMETER_NOT_ANNOTATED {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format("Call site parameter not annotated, found at index '%s'", index);
    }
  },

  ADVICE_PARAMETER_THIS_DUPLICATED {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @This is duplicated, found at index '%s'", index);
    }
  },

  ADVICE_PARAMETER_THIS_SHOULD_BE_FIRST {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @This should be the first, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_THIS_ON_STATIC_METHOD {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter can't be annotated with this for static pointcut methods, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_RETURN_DUPLICATED {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @Return duplicated, found at index '%s'", index);
    }
  },

  ADVICE_PARAMETER_ARGUMENT_OUT_OF_BOUNDS {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      final int paramIndex = (int) objects[1];
      return String.format(
          "Call site parameter annotated with @Parameter(%s) out of bounds for pointcut, found at index '%s'",
          paramIndex, index);
    }
  },

  ADVICE_PARAMETER_ARGUMENT_DUPLICATED {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      final int paramIndex = (int) objects[1];
      return String.format(
          "Call site parameter annotated with @Parameter(%s) is duplicated , found at index '%s'",
          paramIndex, index);
    }
  },

  ADVICE_PARAMETER_ARGUMENT_SHOULD_BE_IN_ORDER {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      final int paramIndex = (int) objects[1];
      return String.format(
          "Call site parameter annotated with @Parameter(%s) not in ascending order, found at index '%s'",
          paramIndex, index);
    }
  },

  ADVICE_PARAMETER_RETURN_SHOULD_BE_LAST {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @Return should be last, found at index '%s'", index);
    }
  },

  ADVICE_POINTCUT_CONSTRUCTOR_NOT_VOID {
    @Override
    public String apply(final Object[] objects) {
      final MethodType pointcut = (MethodType) objects[0];
      return String.format(
          "Pointcut return type should be void for constructors, found '%s'",
          pointcut.getMethodType().getReturnType().getClassName());
    }
  },

  ADVICE_BEFORE_SHOULD_RETURN_VOID {
    @Override
    public String apply(final Object[] objects) {
      final MethodType advice = (MethodType) objects[0];
      return String.format(
          "Before advice should return void, received '%s'",
          advice.getMethodType().getReturnType().getClassName());
    }
  },

  ADVICE_BEFORE_SHOULD_NOT_CONTAIN_RETURN {
    @Override
    public String apply(final Object[] objects) {
      return "Before advice should not contain @Return annotated parameters";
    }
  },

  ADVICE_BEFORE_CTOR_SHOULD_NOT_CONTAIN_THIS {
    @Override
    public String apply(final Object[] objects) {
      return "Before advice should not contain @This annotated parameters in constructors";
    }
  },

  ADVICE_AROUND_SHOULD_NOT_RETURN_VOID {
    @Override
    public String apply(final Object[] objects) {
      return "Around advice should not return void";
    }
  },

  ADVICE_AROUND_POINTCUT_CTOR {
    @Override
    public String apply(final Object[] objects) {
      return "Around advice not yet supported for constructors";
    }
  },

  ADVICE_AROUND_SHOULD_NOT_CONTAIN_RETURN {
    @Override
    public String apply(final Object[] objects) {
      return "Around advice should not contain @Return annotated parameters";
    }
  },

  ADVICE_AFTER_SHOULD_NOT_RETURN_VOID {
    @Override
    public String apply(final Object[] objects) {
      return "After advice should not return void";
    }
  },

  ADVICE_AFTER_CTOR_FIRST_ARG_SHOULD_BE_THIS {
    @Override
    public String apply(final Object[] objects) {
      return "After advice first parameter should be annotated with @This for constructors";
    }
  },

  ADVICE_AFTER_LAST_ARG_SHOULD_BE_RETURN {
    @Override
    public String apply(final Object[] objects) {
      return "After advice last parameter should be annotated with @Return for non constructors";
    }
  },

  POINTCUT_SIGNATURE_INVALID {
    @Override
    public String apply(final Object[] objects) {
      final String signature = (String) objects[0];
      final String pattern = (String) objects[1];
      return String.format(
          "Pointcut signature '%s' does not match the regular expression '%s'", signature, pattern);
    }
  },

  POINTCUT_SIGNATURE_INVALID_TYPE {
    @Override
    public String apply(final Object[] objects) {
      final String signature = (String) objects[0];
      final String type = (String) objects[1];
      return String.format("Pointcut signature '%s' contains in valid type '%s'", signature, type);
    }
  },

  ADVICE_ILLEGAL_STACK_MANIPULATION {
    @Override
    public String apply(final Object[] objects) {
      final AdviceSpecification spec = (AdviceSpecification) objects[0];
      return String.format(
          "Cannot manipulate stack to adapt pointcut '%s' to call site '%s' (Around advice does not have this limitation)",
          spec.getPointcut(), spec.getAdvice());
    }
  },

  // Others

  UNCAUGHT_ERROR {
    @Override
    public String apply(final Object[] objects) {
      return "Uncaught error";
    }
  }
}
