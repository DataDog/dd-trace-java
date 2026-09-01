package datadog.trace.agent.tooling.bytebuddy.outline;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.bytebuddy.memoize.MemoizedMatchers;
import java.util.concurrent.Callable;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

class TypeFactoryTest {
  @Test
  void reusesCachedDescriptionForRegularTransformationTarget() {
    String name = getClass().getName() + "$RegularTarget";

    assertEquals(
        Runnable.class.getName(), resolveInterface(name, bytes(name, Runnable.class), false));
    assertEquals(
        Runnable.class.getName(), resolveInterface(name, bytes(name, Callable.class), false));
  }

  @Test
  void rebuildsLambdaTransformationTargetFromSuppliedBytes() {
    String name = getClass().getName() + "$LambdaTarget";

    assertEquals(
        Runnable.class.getName(), resolveInterface(name, bytes(name, Runnable.class), false));
    assertEquals(
        Callable.class.getName(), resolveInterface(name, bytes(name, Callable.class), true));
  }

  @Test
  void doesNotCacheLambdaTransformationTarget() {
    String name = getClass().getName() + "$UncachedLambdaTarget";

    assertEquals(
        Runnable.class.getName(), resolveInterface(name, bytes(name, Runnable.class), true));
    assertEquals(
        Callable.class.getName(), resolveInterface(name, bytes(name, Callable.class), false));
  }

  @Test
  void doesNotMemoizeLambdaMatcherResults() {
    ElementMatcher<TypeDescription> implementsRunnable =
        new MemoizedMatchers().hasInterface(named(Runnable.class.getName()));

    String lambdaFirstName = getClass().getName() + "$LambdaMatcherFirst";
    assertTrue(
        matches(lambdaFirstName, bytes(lambdaFirstName, Runnable.class), true, implementsRunnable));
    assertFalse(
        matches(
            lambdaFirstName, bytes(lambdaFirstName, Callable.class), false, implementsRunnable));

    String lambdaSecondName = getClass().getName() + "$LambdaMatcherSecond";
    assertFalse(
        matches(
            lambdaSecondName, bytes(lambdaSecondName, Callable.class), false, implementsRunnable));
    assertTrue(
        matches(
            lambdaSecondName, bytes(lambdaSecondName, Runnable.class), true, implementsRunnable));
  }

  @Test
  void doesNotReuseCachedVisibilityForLambda() {
    ElementMatcher<TypeDescription> isPublic = isPublic();
    String name = getClass().getName() + "$LambdaVisibility";

    assertTrue(matches(name, bytes(name, Runnable.class, Visibility.PUBLIC), false, isPublic));
    assertFalse(
        matches(name, bytes(name, Runnable.class, Visibility.PACKAGE_PRIVATE), true, isPublic));
  }

  private static String resolveInterface(String name, byte[] bytecode, boolean lambda) {
    TypeFactory typeFactory = TypeFactory.typeFactory.get();
    typeFactory.switchContext(TypeFactoryTest.class.getClassLoader());
    if (lambda) {
      typeFactory.beginLambdaTransform(Runnable.class.getName());
    }
    typeFactory.beginTransform(name, bytecode);
    try {
      TypeDescription type = TypeFactory.findType(name);
      return type.getInterfaces().getOnly().asErasure().getName();
    } finally {
      typeFactory.endTransform();
      if (lambda) {
        typeFactory.endLambdaTransform();
      }
    }
  }

  private static boolean matches(
      String name, byte[] bytecode, boolean lambda, ElementMatcher<TypeDescription> matcher) {
    TypeFactory typeFactory = TypeFactory.typeFactory.get();
    typeFactory.switchContext(TypeFactoryTest.class.getClassLoader());
    if (lambda) {
      typeFactory.beginLambdaTransform(Runnable.class.getName());
    }
    typeFactory.beginTransform(name, bytecode);
    try {
      return matcher.matches(TypeFactory.findType(name));
    } finally {
      typeFactory.endTransform();
      if (lambda) {
        typeFactory.endLambdaTransform();
      }
    }
  }

  private static byte[] bytes(String name, Class<?> implementedInterface) {
    return bytes(name, implementedInterface, Visibility.PUBLIC);
  }

  private static byte[] bytes(String name, Class<?> implementedInterface, Visibility visibility) {
    return new ByteBuddy()
        .subclass(Object.class)
        .name(name)
        .implement(implementedInterface)
        .modifiers(visibility)
        .make()
        .getBytes();
  }
}
