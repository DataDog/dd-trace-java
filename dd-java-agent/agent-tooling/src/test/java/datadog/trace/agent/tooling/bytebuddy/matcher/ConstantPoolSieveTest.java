package datadog.trace.agent.tooling.bytebuddy.matcher;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class ConstantPoolSieveTest {

  /** Fixture with long/double constants, which take two constant-pool slots. */
  @SuppressWarnings("unused")
  static class Fixture {
    long alpha() {
      return 123456789012L;
    }

    double beta(String s) {
      return 3.25e100 + s.length();
    }
  }

  @Test
  void findsDeclaredMethodNames() throws IOException {
    ConstantPoolSieve sieve = new ConstantPoolSieve(asList("alpha", "beta", "gamma", "<init>"));
    BitSet present = new BitSet();

    assertTrue(sieve.scan(bytecodeOf(Fixture.class), present));

    assertTrue(present.get(sieve.id("alpha")));
    assertTrue(present.get(sieve.id("beta")));
    assertTrue(present.get(sieve.id("<init>")));
    assertFalse(present.get(sieve.id("gamma")));
  }

  @Test
  void neverMissesADeclaredMethod() throws IOException {
    for (Class<?> type :
        asList(
            Fixture.class,
            ConstantPoolSieve.class,
            String.class,
            HashMap.class,
            ConcurrentHashMap.class,
            Arrays.class)) {
      List<String> names = new ArrayList<>();
      for (Method method : type.getDeclaredMethods()) {
        names.add(method.getName());
      }
      ConstantPoolSieve sieve = new ConstantPoolSieve(names);
      BitSet present = new BitSet();

      assertTrue(sieve.scan(bytecodeOf(type), present), type.getName());

      for (String name : names) {
        int id = sieve.id(name);
        assertTrue(id < 0 || present.get(id), type.getName() + "." + name);
      }
    }
  }

  @Test
  void prefixNeedlesMatchAnyEntryStartingWithThem() throws IOException {
    ConstantPoolSieve sieve = new ConstantPoolSieve(asList("alp*", "gam*", "beta"));
    BitSet present = new BitSet();

    assertTrue(sieve.scan(bytecodeOf(Fixture.class), present));

    assertTrue(present.get(sieve.id("alp*")));
    assertFalse(present.get(sieve.id("gam*")));
    assertTrue(present.get(sieve.id("beta")));
    assertEquals(3, sieve.size());
  }

  @Test
  void emptyPrefixIsNotLookedFor() {
    ConstantPoolSieve sieve = new ConstantPoolSieve(asList("*"));

    assertEquals(0, sieve.size());
    assertEquals(-1, sieve.id("*"));
  }

  @Test
  void deduplicatesNames() {
    ConstantPoolSieve sieve = new ConstantPoolSieve(asList("run", "run", "call"));

    assertEquals(2, sieve.size());
    assertEquals(sieve.id("run"), sieve.id("run"));
  }

  @Test
  void rejectsTruncatedClassFiles() throws IOException {
    ConstantPoolSieve sieve = new ConstantPoolSieve(asList("alpha"));
    byte[] bytecode = bytecodeOf(Fixture.class);

    assertFalse(sieve.scan(Arrays.copyOf(bytecode, 40), new BitSet()));
    assertFalse(sieve.scan(new byte[4], new BitSet()));
  }

  @Test
  void doesNotLookForNamesItCannotMatchExactly() {
    ConstantPoolSieve sieve = new ConstantPoolSieve(asList("café", ""));

    assertEquals(0, sieve.size());
    assertEquals(-1, sieve.id("café"));
    assertEquals(-1, sieve.id("unknown"));
  }

  static byte[] bytecodeOf(Class<?> type) throws IOException {
    String resource = type.getName().replace('.', '/') + ".class";
    ClassLoader loader = type.getClassLoader();
    try (InputStream in =
        null != loader
            ? loader.getResourceAsStream(resource)
            : ClassLoader.getSystemResourceAsStream(resource)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) > 0) {
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }
}
