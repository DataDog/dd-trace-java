package datadog.trace.instrumentation.servlet3;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
import static net.bytebuddy.jar.asm.Opcodes.ALOAD;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESPECIAL;
import static net.bytebuddy.jar.asm.Opcodes.RETURN;
import static net.bytebuddy.jar.asm.Opcodes.V1_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import org.junit.jupiter.api.Test;

class HttpServletExtractAdapterTest {

  private static final HttpServletExtractAdapter.Response GETTER =
      HttpServletExtractAdapter.Response.GETTER;

  @Test
  void readsHeadersFromSupportedResponse() {
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(supportedResponse(), collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
  }

  @Test
  void skipsResponseClassWithoutHeaderAccessors() throws Exception {
    // sanity check: calling the Servlet 3.0 method on this class really fails
    HttpServletResponse old = newOldResponse("OldResponse");
    assertThrows(AbstractMethodError.class, old::getHeaderNames);
    List<String> seen = new ArrayList<>();

    // the first response of the class trips the failure path
    GETTER.forEachKey(old, collectInto(seen));
    // later responses of the same class take the remembered path
    GETTER.forEachKey(old, collectInto(seen));

    assertEquals(emptyList(), seen);
  }

  @Test
  void readsHeadersFromWrappedDelegate() throws Exception {
    HttpServletResponse oldWrapper = newOldWrapper("OldWrapper", supportedResponse());
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(oldWrapper, collectInto(seen));
    GETTER.forEachKey(oldWrapper, collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2", "x-a=1", "x-b=2"), seen);
  }

  @Test
  void probeDistinguishesClassesWithAndWithoutHeaderAccessors() throws Exception {
    // a concrete class that never implemented the Servlet 3.0 methods still exposes the abstract
    // interface method through getMethod, which is what the probe relies on
    assertFalse(
        HttpServletExtractAdapter.Response.supportsHeaderAccess(
            newOldResponse("OldResponseProbe").getClass()));
    assertFalse(
        HttpServletExtractAdapter.Response.supportsHeaderAccess(
            newOldWrapper("OldWrapperProbe", supportedResponse()).getClass()));
    assertTrue(
        HttpServletExtractAdapter.Response.supportsHeaderAccess(supportedResponse().getClass()));
    assertTrue(
        HttpServletExtractAdapter.Response.supportsHeaderAccess(HttpServletResponseWrapper.class));
  }

  @Test
  void healthyResponsesStillWorkAfterAFailure() throws Exception {
    // tests can run in any order, so trip the failure path here to cover healthy responses after it
    List<String> failed = new ArrayList<>();
    GETTER.forEachKey(newOldResponse("OldResponseTwo"), collectInto(failed));
    assertEquals(emptyList(), failed);
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(supportedResponse(), collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
  }

  @Test
  void callbackFailureDoesNotMarkResponseClassUnsupported() {
    // an AbstractMethodError raised by the callback says nothing about the response class
    assertThrows(
        AbstractMethodError.class,
        () ->
            GETTER.forEachKey(
                supportedResponse(),
                (key, value) -> {
                  throw new AbstractMethodError();
                }));
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(supportedResponse(), collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
  }

  private static HttpServletResponse supportedResponse() {
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            HttpServletResponse.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getHeaderNames":
                  return asList("x-a", "x-b");
                case "getHeader":
                  return "x-a".equals(args[0]) ? "1" : "2";
                default:
                  return null;
              }
            });
  }

  private static AgentPropagation.KeyClassifier collectInto(List<String> seen) {
    return (key, value) -> {
      seen.add(key + "=" + value);
      return true;
    };
  }

  /** Defines a class that implements {@link HttpServletResponse} but none of its methods. */
  private static HttpServletResponse newOldResponse(String name) throws Exception {
    return (HttpServletResponse)
        define(name, "java/lang/Object", "()V").getConstructor().newInstance();
  }

  /** Like {@link #newOldResponse} but extends {@code ServletResponseWrapper}. */
  private static HttpServletResponse newOldWrapper(String name, ServletResponse delegate)
      throws Exception {
    return (HttpServletResponse)
        define(name, "javax/servlet/ServletResponseWrapper", "(Ljavax/servlet/ServletResponse;)V")
            .getConstructor(ServletResponse.class)
            .newInstance(delegate);
  }

  /** The constructor just forwards its (optional) argument to the same-shaped super constructor. */
  private static Class<?> define(String name, String superName, String ctorDesc) throws Exception {
    String internalName = "test/" + name;
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        V1_8,
        ACC_PUBLIC,
        internalName,
        null,
        superName,
        new String[] {"javax/servlet/http/HttpServletResponse"});
    MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", ctorDesc, null, null);
    init.visitCode();
    init.visitVarInsn(ALOAD, 0);
    if (!"()V".equals(ctorDesc)) {
      init.visitVarInsn(ALOAD, 1);
    }
    init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", ctorDesc, false);
    init.visitInsn(RETURN);
    init.visitMaxs(2, 2);
    init.visitEnd();
    writer.visitEnd();
    byte[] bytes = writer.toByteArray();
    return new ClassLoader(HttpServletExtractAdapterTest.class.getClassLoader()) {
      Class<?> define() {
        return defineClass("test." + name, bytes, 0, bytes.length);
      }
    }.define();
  }
}
