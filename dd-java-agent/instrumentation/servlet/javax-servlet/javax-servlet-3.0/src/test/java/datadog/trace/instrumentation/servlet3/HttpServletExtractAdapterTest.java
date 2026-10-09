package datadog.trace.instrumentation.servlet3;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static net.bytebuddy.jar.asm.Opcodes.ACC_PUBLIC;
import static net.bytebuddy.jar.asm.Opcodes.ALOAD;
import static net.bytebuddy.jar.asm.Opcodes.ARETURN;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESPECIAL;
import static net.bytebuddy.jar.asm.Opcodes.INVOKESTATIC;
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
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import org.junit.jupiter.api.Test;

class HttpServletExtractAdapterTest {

  private static final HttpServletExtractAdapter.Response GETTER =
      HttpServletExtractAdapter.Response.GETTER;
  private static final HttpServletExtractAdapter.Response.HeaderAccessLatch HEADER_LATCH =
      HttpServletExtractAdapter.Response.HEADER_LATCH;

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

  @Test
  void latchesResponseClassWithoutHeaderAccessors() throws Exception {
    warmAccessorCallSites();
    HttpServletResponse old = newOldResponse("OldResponseLatched");
    assertFalse(HEADER_LATCH.isLatched(old));

    GETTER.forEachKey(old, collectInto(new ArrayList<>()));

    assertTrue(HEADER_LATCH.isLatched(old));
    assertFalse(HEADER_LATCH.isLatched(supportedResponse()));
  }

  @Test
  void latchesResponseClassMissingOnlyGetHeader() throws Exception {
    warmAccessorCallSites();
    // not producible by javac, but bytecode generators can implement one accessor and not the other
    HttpServletResponse partial = newPartialResponse("PartialResponse");
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(partial, collectInto(seen));
    GETTER.forEachKey(partial, collectInto(seen));

    assertEquals(emptyList(), seen);
    assertTrue(HEADER_LATCH.isLatched(partial));
  }

  @Test
  void oldWrapperSubclassInheritsHeaderAccessors() throws Exception {
    // a wrapper compiled against Servlet 2.5 inherits the container's delegating accessors, which
    // is why these classes need no unwrapping
    HttpServletResponse oldWrapper = newOldWrapper("OldWrapper", supportedResponse());
    List<String> seen = new ArrayList<>();

    GETTER.forEachKey(oldWrapper, collectInto(seen));

    assertEquals(asList("x-a=1", "x-b=2"), seen);
    assertFalse(HEADER_LATCH.isLatched(oldWrapper));
  }

  /**
   * Calls the accessors on a working class first. JDK 8 then throws the AbstractMethodError without
   * a message, as it does in production, where the call sites have seen healthy responses.
   */
  private static void warmAccessorCallSites() {
    GETTER.forEachKey(supportedResponse(), collectInto(new ArrayList<>()));
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
        define(name, "java/lang/Object", "()V", true, false).getConstructor().newInstance();
  }

  /** Like {@link #newOldResponse}, but implements {@code getHeaderNames} (one name) only. */
  private static HttpServletResponse newPartialResponse(String name) throws Exception {
    return (HttpServletResponse)
        define(name, "java/lang/Object", "()V", true, true).getConstructor().newInstance();
  }

  /** A {@code HttpServletResponseWrapper} subclass that, like 2.5 code, overrides nothing. */
  private static HttpServletResponse newOldWrapper(String name, HttpServletResponse delegate)
      throws Exception {
    return (HttpServletResponse)
        define(
                name,
                "javax/servlet/http/HttpServletResponseWrapper",
                "(Ljavax/servlet/http/HttpServletResponse;)V",
                false,
                false)
            .getConstructor(HttpServletResponse.class)
            .newInstance(delegate);
  }

  /** The constructor just forwards its (optional) argument to the same-shaped super constructor. */
  private static Class<?> define(
      String name,
      String superName,
      String ctorDesc,
      boolean implementsResponse,
      boolean withHeaderNames)
      throws Exception {
    String internalName = "test/" + name;
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        V1_8,
        ACC_PUBLIC,
        internalName,
        null,
        superName,
        implementsResponse ? new String[] {"javax/servlet/http/HttpServletResponse"} : null);
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
    if (withHeaderNames) {
      MethodVisitor names =
          writer.visitMethod(ACC_PUBLIC, "getHeaderNames", "()Ljava/util/Collection;", null, null);
      names.visitCode();
      names.visitLdcInsn("x-a");
      names.visitMethodInsn(
          INVOKESTATIC,
          "java/util/Collections",
          "singletonList",
          "(Ljava/lang/Object;)Ljava/util/List;",
          false);
      names.visitInsn(ARETURN);
      names.visitMaxs(1, 1);
      names.visitEnd();
    }
    writer.visitEnd();
    byte[] bytes = writer.toByteArray();
    return new ClassLoader(HttpServletExtractAdapterTest.class.getClassLoader()) {
      Class<?> define() {
        return defineClass("test." + name, bytes, 0, bytes.length);
      }
    }.define();
  }
}
