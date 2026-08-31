package datadog.trace.agent.tooling.bytebuddy.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.Callable;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
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

  private static String resolveInterface(String name, byte[] bytecode, boolean lambda) {
    TypeFactory typeFactory = TypeFactory.typeFactory.get();
    typeFactory.switchContext(TypeFactoryTest.class.getClassLoader());
    if (lambda) {
      typeFactory.beginLambdaTransform();
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

  private static byte[] bytes(String name, Class<?> implementedInterface) {
    return new ByteBuddy()
        .subclass(Object.class)
        .name(name)
        .implement(implementedInterface)
        .make()
        .getBytes();
  }
}
