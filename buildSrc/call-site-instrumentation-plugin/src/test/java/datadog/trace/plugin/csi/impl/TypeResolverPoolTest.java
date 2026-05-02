package datadog.trace.plugin.csi.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.plugin.csi.util.MethodType;
import java.lang.reflect.Method;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

class TypeResolverPoolTest {

  TypeResolverPool resolver = new TypeResolverPool();

  @Test
  void testResolvePrimitive() {
    assertEquals(int.class, resolver.resolveType(Type.INT_TYPE));
  }

  @Test
  void testResolvePrimitiveArray() {
    Type type = Type.getType("[I");
    assertEquals(int[].class, resolver.resolveType(type));
  }

  @Test
  void testResolvePrimitiveMultidimensionalArray() {
    Type type = Type.getType("[[[I");
    assertEquals(int[][][].class, resolver.resolveType(type));
  }

  @Test
  void testResolveClass() {
    Type type = Type.getType(String.class);
    assertEquals(String.class, resolver.resolveType(type));
  }

  @Test
  void testResolveClassArray() {
    Type type = Type.getType(String[].class);
    assertEquals(String[].class, resolver.resolveType(type));
  }

  @Test
  void testResolveClassMultidimensionalArray() {
    Type type = Type.getType(String[][][].class);
    assertEquals(String[][][].class, resolver.resolveType(type));
  }

  @Test
  void testTypeResolverFromMethod() {
    Type type =
        Type.getMethodType(
            Type.getType(String[].class), Type.getType(String.class), Type.getType(String.class));
    assertEquals(String[].class, resolver.resolveType(type.getReturnType()));
  }

  @Test
  void testInheritedMethods() throws Exception {
    Type owner = Type.getType(HttpServletRequest.class);
    String name = "getParameter";
    Type descriptor = Type.getMethodType(Type.getType(String.class), Type.getType(String.class));
    Method result = (Method) resolver.resolveMethod(new MethodType(owner, name, descriptor));
    assertEquals(ServletRequest.class.getDeclaredMethod("getParameter", String.class), result);
  }
}
