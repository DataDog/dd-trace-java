package datadog.trace.plugin.csi.util;

import java.lang.reflect.Method;
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

  CALL_SITE_ENABLED_SHOULD_BE_STATIC_AND_PUBLIC {
    @Override
    public String apply(final Object[] objects) {
      final Method method = (Method) objects[0];
      return String.format(
          "@CallSite enabled method should be static and public, received '%s'", method);
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
      final Type pointcut = (Type) objects[0];
      final Type callSite = (Type) objects[1];
      return String.format(
          "Call site return '%s' not compatible with pointcut return '%s'",
          callSite.getClassName(), pointcut.getClassName());
    }
  },

  ADVICE_METHOD_PARAM_THIS_NOT_COMPATIBLE {
    @Override
    public String apply(final Object[] objects) {
      final Type pointcut = (Type) objects[0];
      final Type callSite = (Type) objects[1];
      return String.format(
          "Call site @This with type '%s' not compatible with pointcut owner '%s'",
          callSite.getClassName(), pointcut.getClassName());
    }
  },

  ADVICE_METHOD_PARAM_RETURN_NOT_COMPATIBLE {
    @Override
    public String apply(final Object[] objects) {
      final Type pointcut = (Type) objects[0];
      final Type callSite = (Type) objects[1];
      return String.format(
          "Call site @Return with type '%s' not compatible with pointcut owner '%s'",
          callSite.getClassName(), pointcut.getClassName());
    }
  },

  ADVICE_METHOD_PARAM_ALL_ARGS_NOT_COMPATIBLE {
    @Override
    public String apply(final Object[] objects) {
      final Type pointcut = (Type) objects[0];
      final Type callSite = (Type) objects[1];
      return String.format(
          "Call site @AllArguments with type '%s' not compatible with '%s'",
          callSite.getClassName(), pointcut.getClassName());
    }
  },

  ADVICE_METHOD_PARAM_NOT_COMPATIBLE {
    @Override
    public String apply(final Object[] objects) {
      final Type pointcut = (Type) objects[0];
      final Type callSite = (Type) objects[1];
      final int index = (int) objects[2];
      return String.format(
          "Call site @Argument with type '%s' not compatible with pointcut parameter type '%s', found at index '%s'",
          callSite.getClassName(), pointcut.getClassName(), index);
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
          "Call site parameter can't be annotated with @This for static pointcut methods, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_THIS_ON_INVOKE_DYNAMIC {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter can't be annotated with @This for invoke dynamic instructions, found at index '%s'",
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

  ADVICE_PARAMETER_ALL_ARGS_DUPLICATED {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @AllArguments duplicated, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_DUPLICATED {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @InvokeDynamicConstants duplicated, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_NOT_COMPATIBLE {
    @Override
    public String apply(final Object[] objects) {
      final Type pointcut = (Type) objects[0];
      final Type callSite = (Type) objects[1];
      return String.format(
          "Call site @InvokeDynamicConstants with type '%s' not compatible with '%s'",
          callSite.getClassName(), pointcut.getClassName());
    }
  },

  ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_SHOULD_BE_LAST {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @InvokeDynamicConstants should be the last, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_NON_AFTER_ADVICE {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @InvokeDynamicConstants should only be used on @After call sites, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_INVOKE_DYNAMIC_CONSTANTS_ON_NON_INVOKE_DYNAMIC {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @InvokeDynamicConstants should target only invoke dynamic instructions, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_ALL_ARGS_MIXED {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @AllArguments mixed with @Argument, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_ARGUMENT_OUT_OF_BOUNDS {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @Argument out of bounds for pointcut, found at index '%s'",
          index);
    }
  },

  ADVICE_PARAMETER_ON_INVOKE_DYNAMIC {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameters can't be annotated with @Argument for invoke dynamic instructions, found at index '%s'",
          index);
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

  ADVICE_PARAMETER_RETURN_NON_AFTER_ADVICE {
    @Override
    public String apply(final Object[] objects) {
      final int index = (int) objects[0];
      return String.format(
          "Call site parameter annotated with @Return should only be used on @After call sites, found at index '%s'",
          index);
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

  ADVICE_AFTER_SHOULD_HAVE_THIS {
    @Override
    public String apply(final Object[] objects) {
      return "After advice first parameter should be annotated with @This or @AllArguments(includeThis = true)";
    }
  },

  ADVICE_AFTER_SHOULD_HAVE_RETURN {
    @Override
    public String apply(final Object[] objects) {
      return "After advice last parameter should be annotated with @Return";
    }
  },

  ADVICE_AFTER_CONSTRUCTOR_ALL_ARGUMENTS {
    @Override
    public String apply(final Object[] objects) {
      return "After advice in constructors should use @AllArguments";
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

  // Errors in extensions

  EXTENSION_ERROR {
    @Override
    public String apply(final Object[] objects) {
      final Class<?> extension = (Class<?>) objects[0];
      return String.format("Failed to apply extension %s", extension);
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
