package com.datadog.iast;

import static net.bytebuddy.matcher.ElementMatchers.named;

import java.lang.reflect.Method;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodCall;

public class AnyStackRunner {

  public static void callWithinStack(
      final String parentClass, final Method method, final Object target, final Object... params) {
    Class<?> dynamicType =
        new ByteBuddy()
            .subclass(Runnable.class)
            .name(parentClass)
            .method(named("run"))
            .intercept(MethodCall.invoke(method).on(target).with(params))
            .make()
            .load(AnyStackRunner.class.getClassLoader())
            .getLoaded();
    try {
      Runnable obj = (Runnable) dynamicType.getDeclaredConstructor().newInstance();
      obj.run();
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
