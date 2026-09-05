package datadog.trace.civisibility.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.civisibility.source.LinesResolver.Lines;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class ByteCodeLinesResolverTest {

  @Test
  void testMethodLinesResolution() throws NoSuchMethodException {
    Method aTestMethod = NestedClass.class.getDeclaredMethod("aTestMethod");

    ByteCodeLinesResolver linesResolver = new ByteCodeLinesResolver();
    Lines methodLines = linesResolver.getMethodLines(aTestMethod);

    assertTrue(methodLines.isValid());
    assertTrue(methodLines.getStartLineNumber() > 0);
    assertTrue(methodLines.getEndLineNumber() > methodLines.getStartLineNumber());
  }

  @Test
  void testAlwaysInvalidClassLinesResolution() {
    ByteCodeLinesResolver linesResolver = new ByteCodeLinesResolver();
    Lines classLines = linesResolver.getClassLines(NestedClass.class);

    assertFalse(classLines.isValid());
  }

  @Test
  void testInvalidMethodLinesResolution() throws NoSuchMethodException {
    Method abstractMethod = NestedClass.class.getDeclaredMethod("abstractMethod");

    ByteCodeLinesResolver linesResolver = new ByteCodeLinesResolver();
    Lines methodLines = linesResolver.getMethodLines(abstractMethod);

    assertFalse(methodLines.isValid());
  }

  @Test
  void testReturnsEmptyMethodLinesWhenClassCannotBeLoaded()
      throws IOException, ClassNotFoundException, NoSuchMethodException {
    MisbehavingClassLoader misbehavingClassLoader = new MisbehavingClassLoader();

    try (InputStream stream = Utils.getClassStream(NestedClass.class)) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int bytesRead;
      while ((bytesRead = stream.read(buffer)) != -1) {
        baos.write(buffer, 0, bytesRead);
      }
      misbehavingClassLoader.putClass(NestedClass.class.getName(), baos.toByteArray());
    }

    Class<?> misbehavingClass = misbehavingClassLoader.loadClass(NestedClass.class.getName());
    Method misbehavingMethod = misbehavingClass.getDeclaredMethod("aTestMethod");

    ByteCodeLinesResolver linesResolver = new ByteCodeLinesResolver();
    Lines methodLines = linesResolver.getMethodLines(misbehavingMethod);

    assertFalse(methodLines.isValid());
  }

  @Test
  void testReturnsEmptyMethodLinesWhenClassResourceIsMissing()
      throws IOException, ClassNotFoundException, NoSuchMethodException {
    // regression test: Utils.getClassStream() returns null (rather than throwing) for
    // classes whose bytecode resource cannot be located (e.g. certain generated/proxy classes)
    NullResourceClassLoader nullResourceClassLoader = new NullResourceClassLoader();

    try (InputStream stream = Utils.getClassStream(NestedClass.class)) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int bytesRead;
      while ((bytesRead = stream.read(buffer)) != -1) {
        baos.write(buffer, 0, bytesRead);
      }
      nullResourceClassLoader.putClass(NestedClass.class.getName(), baos.toByteArray());
    }

    Class<?> unresolvableClass = nullResourceClassLoader.loadClass(NestedClass.class.getName());
    Method unresolvableMethod = unresolvableClass.getDeclaredMethod("aTestMethod");

    ByteCodeLinesResolver linesResolver = new ByteCodeLinesResolver();
    Lines methodLines = linesResolver.getMethodLines(unresolvableMethod);

    assertFalse(methodLines.isValid());
  }

  @Test
  void testReturnsEmptyMethodLinesWhenUnknownMethodIsAttemptedToBeResolved()
      throws NoSuchMethodException {
    Method abstractMethod = NestedClass.class.getDeclaredMethod("abstractMethod");
    ByteCodeLinesResolver.ClassMethodLines classMethodLines =
        new ByteCodeLinesResolver.ClassMethodLines();

    Lines methodLines = classMethodLines.get(abstractMethod);

    assertFalse(methodLines.isValid());
  }

  private abstract static class NestedClass {
    static double aTestMethod() {
      double random = Math.random();
      return random;
    }

    abstract void abstractMethod();
  }
}
