package datadog.trace.agent.tooling.jit;

import static datadog.trace.agent.tooling.jit.StringCallSiteRewriterTest.bytecodeOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class CallSiteWindowsTest {

  private static final Object[][] CALLS = {
    {"formatConstant", "a", 1},
    {"formatConstant", null, -42},
    {"formatDynamic", "<%s>", "x"},
    {"formatDynamic", "%d", "not a number"},
    {"formatLocale", "b", 1234567},
    {"formatInLoop", "z", 3},
    {"formatInTry", "q"},
    {"compileMatches", "aab"},
    {"compileMatches", "ba"},
    {"compileMatches", null},
    {"compileFind", "abc"},
    {"compileLookingAt", "abc"},
    {"compileLookingAt", "bc"},
    {"compileSplit", "a, b,c ,d"},
    {"compileMatchesAcrossLines", "aaa"},
    {"compileNotASimpleLoad", "a", true},
    {"compileNotASimpleLoad", "a", false},
    {"compileInvalid", "a"},
    {"localDate", "2026-11-24"},
    {"localDateTime", "2026-02-03"},
    {"formatterFormat", "2026-07-04"},
    {"decimalFormat", 1234.567},
    {"decimalFormat", -0.005},
    {"decimalFormatDynamic", "0.###E0", 98765.4321},
    {"decimalFormatDynamic", "#,##0.0#;(#)", -12.3},
    {"decimalFormatDynamic", "#.#.#", 1.0},
    {"urlEncode", "plain-text_1.2*"},
    {"urlEncode", "needs encoding & more"},
    {"urlDecode", "plain"},
    {"urlDecode", "a+b%20c"},
  };

  @Test
  void rewrittenClassBehavesLikeTheOriginal() throws Exception {
    byte[] rewritten = StringCallSiteRewriter.rewrite(bytecodeOf(WindowFixture.class));
    assertNotNull(rewritten);
    String pool = new String(rewritten, StandardCharsets.ISO_8859_1);
    for (String helper :
        new String[] {
          "compileMatches",
          "compileFind",
          "compileLookingAt",
          "compileSplit",
          "temporalFormat",
          "formatterFormat",
          "urlEncode",
          "urlDecode",
          "JitSites",
          "format$",
          "formatLocale$"
        }) {
      assertTrue(pool.contains(helper), helper);
    }
    assertEquals(StringCallSiteRewriter.DECIMAL_FORMAT_WINDOW, pool.contains("newDecimalFormat"));

    // initialize, so the verifier checks every method of the rewritten class
    Class<?> patched =
        Class.forName(
            WindowFixture.class.getName(),
            true,
            new StringCallSiteRewriterTest.SingleClassLoader(
                WindowFixture.class.getName(), rewritten));
    for (int pass = 0; pass < 40; pass++) { // past the helpers' promotion thresholds
      for (Object[] call : CALLS) {
        Object[] args = Arrays.copyOfRange(call, 1, call.length);
        Method original = find(WindowFixture.class, (String) call[0], args.length);
        Method method = patched.getMethod(original.getName(), original.getParameterTypes());
        assertSameOutcome(original, method, args);
      }
    }
  }

  @Test
  void windowsWithObservableInsidesAreNotRewritten() throws Exception {
    StringCallSiteRewriter.rejectedWindows.clear();
    StringCallSiteRewriter.rewrite(bytecodeOf(WindowFixture.class));
    // javac puts a line-number entry on each line of a chained call
    assertTrue(
        StringCallSiteRewriter.rejectedWindows.containsKey("compileMatcher:debugInfo"),
        String.valueOf(StringCallSiteRewriter.rejectedWindows));
  }

  @Test
  void stampedChunksHaveTheirOwnState() throws Exception {
    final Map<String, byte[]> stamped = new ConcurrentHashMap<>();
    JitSiteChunks chunks =
        new JitSiteChunks(
            new JitSiteChunks.Definer() {
              @Override
              public void define(String binaryName, byte[] bytecode) {
                stamped.put(binaryName, bytecode);
              }
            });
    // use up chunk 0, which asks for chunk 1 in the background
    for (int i = 0; i < JitSiteChunks.SLOTS; i++) {
      chunks.allocate();
    }
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (chunks.definedChunks() < 2 && System.nanoTime() < deadline) {
      Thread.yield();
    }
    assertEquals(2, chunks.definedChunks());
    String chunk1 = "datadog.trace.bootstrap.jit.JitSites0001";
    assertNotNull(stamped.get(chunk1));

    ClassLoader chunkLoader = new MapClassLoader(stamped);
    byte[] rewritten =
        StringCallSiteRewriter.rewrite(
            bytecodeOf(WindowFixture.class), StringCallSiteRewriter.TARGETS, chunks);
    assertNotNull(rewritten);
    assertTrue(new String(rewritten, StandardCharsets.ISO_8859_1).contains("JitSites0001"));
    Class<?> patched =
        Class.forName(
            WindowFixture.class.getName(),
            true,
            new ChildClassLoader(chunkLoader, WindowFixture.class.getName(), rewritten));
    for (int pass = 0; pass < 40; pass++) {
      assertEquals(
          WindowFixture.formatConstant("s", pass),
          patched.getMethod("formatConstant", String.class, int.class).invoke(null, "s", pass));
    }
    Field own = Class.forName(chunk1, true, chunkLoader).getDeclaredField("SITES");
    Field template =
        Class.forName("datadog.trace.bootstrap.jit.JitSites0000").getDeclaredField("SITES");
    own.setAccessible(true);
    template.setAccessible(true);
    assertNotSame(template.get(null), own.get(null));
  }

  @Test
  void stampingOnlyChangesTheName() throws Exception {
    JitSiteChunks chunks =
        new JitSiteChunks(
            new JitSiteChunks.Definer() {
              @Override
              public void define(String binaryName, byte[] bytecode) {}
            });
    byte[] template = bytecodeOf(Class.forName("datadog.trace.bootstrap.jit.JitSites0000"));
    byte[] copy = chunks.stamp(7);
    assertEquals(template.length, copy.length);
    String before = new String(template, StandardCharsets.ISO_8859_1);
    String after = new String(copy, StandardCharsets.ISO_8859_1);
    // every mention of the internal name changed (including any inside descriptors), nothing else
    String name = "datadog/trace/bootstrap/jit/JitSites000";
    assertFalse(after.contains(name + "0"));
    assertEquals(before.replace(name + "0", name + "7"), after);
  }

  private static Method find(Class<?> type, String name, int parameters) {
    for (Method method : type.getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == parameters) {
        return method;
      }
    }
    throw new AssertionError(name);
  }

  private static void assertSameOutcome(Method expected, Method actual, Object[] args) {
    Object expectedResult = outcome(expected, args);
    Object actualResult = outcome(actual, args);
    String what = expected.getName() + Arrays.toString(args);
    if (expectedResult instanceof Object[]) {
      assertArrayEquals((Object[]) expectedResult, (Object[]) actualResult, what);
    } else {
      assertEquals(expectedResult, actualResult, what);
    }
  }

  private static Object outcome(Method method, Object[] args) {
    try {
      return method.invoke(null, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      List<String> d = new ArrayList<>();
      d.add(cause.getClass().getName());
      if (!(cause instanceof NullPointerException)) {
        d.add(String.valueOf(cause.getMessage()));
      }
      return d;
    } catch (IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /** Defines classes from a map of stamped class files; everything else goes to the test loader. */
  static final class MapClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes;

    MapClassLoader(Map<String, byte[]> classes) {
      super(CallSiteWindowsTest.class.getClassLoader());
      this.classes = classes;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      byte[] bytecode = classes.get(name);
      if (null == bytecode) {
        throw new ClassNotFoundException(name);
      }
      return defineClass(name, bytecode, 0, bytecode.length);
    }
  }

  /** Defines one class, child-first, under the given parent. */
  static final class ChildClassLoader extends ClassLoader {
    private final String name;
    private final byte[] bytecode;

    ChildClassLoader(ClassLoader parent, String name, byte[] bytecode) {
      super(parent);
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
  }
}
