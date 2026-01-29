package datadog.trace.plugin.csi.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.plugin.csi.util.MethodType;
import java.lang.reflect.Method;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class TypeResolverPoolTest {

  @Test
  void testResolvePrimitive() {
    TypeResolverPool resolver = new TypeResolverPool();

    Class<?> result = resolver.resolveType(Type.INT_TYPE);

    assertEquals(int.class, result);
  }

  @Test
  void testResolvePrimitiveArray() {
    TypeResolverPool resolver = new TypeResolverPool();
    Type type = Type.getType("[I");

    Class<?> result = resolver.resolveType(type);

    assertEquals(int[].class, result);
  }

  @Test
  void testResolvePrimitiveMultidimensionalArray() {
    TypeResolverPool resolver = new TypeResolverPool();
    Type type = Type.getType("[[[I");

    Class<?> result = resolver.resolveType(type);

    assertEquals(int[][][].class, result);
  }

  @Test
  void testResolveClass() {
    TypeResolverPool resolver = new TypeResolverPool();
    Type type = Type.getType(String.class);

    Class<?> result = resolver.resolveType(type);

    assertEquals(String.class, result);
  }

  @Test
  void testResolveClassArray() {
    TypeResolverPool resolver = new TypeResolverPool();
    Type type = Type.getType(String[].class);

    Class<?> result = resolver.resolveType(type);

    assertEquals(String[].class, result);
  }

  @Test
  void testResolveClassMultidimensionalArray() {
    TypeResolverPool resolver = new TypeResolverPool();
    Type type = Type.getType(String[][][].class);

    Class<?> result = resolver.resolveType(type);

    assertEquals(String[][][].class, result);
  }

  @Test
  void testTypeResolverFromMethod() {
    TypeResolverPool resolver = new TypeResolverPool();
    Type type =
        Type.getMethodType(
            Type.getType(String[].class), Type.getType(String.class), Type.getType(String.class));

    Class<?> result = resolver.resolveType(type.getReturnType());

    assertEquals(String[].class, result);
  }

  @Test
  void testInheritedMethods() throws Exception {
    TypeResolverPool resolver = new TypeResolverPool();
    Type owner = Type.getType(HttpServletRequest.class);
    String name = "getParameter";
    Type descriptor = Type.getMethodType(Type.getType(String.class), Type.getType(String.class));

    Method result = (Method) resolver.resolveMethod(new MethodType(owner, name, descriptor));

    assertEquals(ServletRequest.class.getDeclaredMethod("getParameter", String.class), result);
  }
}
