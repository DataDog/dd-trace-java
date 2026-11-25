package datadog.trace.agent.tooling.jit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StringCallSiteRewriterTest {

  private static final String[] INPUTS = {
    "", "a b  c", " a , b ,c ", "a::b::c::", "x.y.z", "hello42", "xay", "a.b c", "abc"
  };

  @Test
  void rewrittenClassBehavesLikeTheOriginal() throws Exception {
    byte[] original = bytecodeOf(RewriteFixture.class);
    byte[] rewritten = StringCallSiteRewriter.rewrite(original);
    assertNotNull(rewritten);
    assertTrue(
        new String(rewritten, StandardCharsets.ISO_8859_1)
            .contains("datadog/trace/bootstrap/jit/StringRewrites"));

    Class<?> patched = new SingleClassLoader(RewriteFixture.class.getName(), rewritten).load();
    for (Method method : RewriteFixture.class.getDeclaredMethods()) {
      if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      Method patchedMethod = patched.getMethod(method.getName(), method.getParameterTypes());
      for (String input : INPUTS) {
        for (int k : new int[] {0, 1, 2, 3, 99, -7}) {
          Object[] args =
              method.getParameterCount() == 1 ? new Object[] {input} : new Object[] {input, k};
          assertEquals(
              method.invoke(null, args),
              patchedMethod.invoke(null, args),
              method.getName() + Arrays.toString(args));
        }
      }
    }
  }

  @Test
  void classesWithoutTargetsAreLeftAlone() throws IOException {
    assertNull(StringCallSiteRewriter.rewrite(bytecodeOf(NoTargetsFixture.class)));
  }

  @Test
  void malformedInputIsLeftAlone() throws IOException {
    byte[] original = bytecodeOf(RewriteFixture.class);
    assertNull(StringCallSiteRewriter.rewrite(Arrays.copyOf(original, 64)));
    assertNull(StringCallSiteRewriter.rewrite(new byte[] {1, 2, 3}));
  }

  @Test
  void transformerSkipsBootstrapAndAgentClasses() throws IOException {
    byte[] original = bytecodeOf(RewriteFixture.class);
    StringCallSiteRewriter rewriter = new StringCallSiteRewriter();
    ClassLoader loader = getClass().getClassLoader();
    assertNull(rewriter.transform(null, "com/example/Foo", null, null, original));
    assertNull(rewriter.transform(loader, "datadog/trace/Foo", null, null, original));
    assertNull(rewriter.transform(loader, "java/lang/Foo", null, null, original));
    ClassLoader agentLikeLoader = new ClassLoader(loader) {}; // named datadog.*, like ours
    assertNull(rewriter.transform(agentLikeLoader, "net/bytebuddy/Foo", null, null, original));
    assertNotNull(rewriter.transform(loader, "com/example/Foo", null, null, original));
  }

  static byte[] bytecodeOf(Class<?> type) throws IOException {
    String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }

  /** Defines one class from the given bytes; delegates everything else to the test loader. */
  static final class SingleClassLoader extends ClassLoader {
    private final String name;
    private final byte[] bytecode;

    SingleClassLoader(String name, byte[] bytecode) {
      super(StringCallSiteRewriterTest.class.getClassLoader());
      this.name = name;
      this.bytecode = bytecode;
    }

    @Override
    protected Class<?> loadClass(String className, boolean resolve) throws ClassNotFoundException {
      if (name.equals(className)) {
        synchronized (getClassLoadingLock(className)) {
          Class<?> c = findLoadedClass(className);
          if (null == c) {
            c = defineClass(className, bytecode, 0, bytecode.length);
          }
          return c;
        }
      }
      return super.loadClass(className, resolve);
    }

    Class<?> load() throws ClassNotFoundException {
      return loadClass(name);
    }
  }
}
