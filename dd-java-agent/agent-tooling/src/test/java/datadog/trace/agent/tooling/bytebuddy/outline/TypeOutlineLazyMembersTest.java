package datadog.trace.agent.tooling.bytebuddy.outline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.instrument.classmatch.ClassFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import org.junit.jupiter.api.Test;

class TypeOutlineLazyMembersTest {

  @SuppressWarnings("unused")
  abstract static class Fixture implements Runnable {
    private int count;
    protected String label;

    abstract void work(String input);

    @Override
    public void run() {}
  }

  @Test
  void headerIsAvailableWithoutParsingMembers() throws IOException {
    byte[] bytecode = bytecodeOf(Fixture.class);
    TypeOutline lazy = new TypeOutline(ClassFile.header(bytecode), bytecode);
    TypeOutline eager = new TypeOutline(ClassFile.outline(bytecode));

    assertEquals(eager.getName(), lazy.getName());
    assertEquals(eager.getModifiers(), lazy.getModifiers());
    assertEquals(eager.isAbstract(), lazy.isAbstract());
    assertSame(bytecode, lazy.unparsedBytecode());
  }

  @Test
  void membersMatchEagerParsing() throws IOException {
    byte[] bytecode = bytecodeOf(Fixture.class);
    TypeOutline lazy = new TypeOutline(ClassFile.header(bytecode), bytecode);
    TypeOutline eager = new TypeOutline(ClassFile.outline(bytecode));

    assertEquals(describeMethods(eager), describeMethods(lazy));
    assertEquals(describeFields(eager), describeFields(lazy));
    assertEquals(eager.getDeclaredAnnotations().size(), lazy.getDeclaredAnnotations().size());
    assertNull(lazy.unparsedBytecode());
  }

  @Test
  void concurrentFirstAccessParsesMembersOnce() throws Exception {
    byte[] bytecode = bytecodeOf(Fixture.class);
    List<String> expected = describeMethods(new TypeOutline(ClassFile.outline(bytecode)));
    ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      for (int round = 0; round < 50; round++) {
        TypeOutline lazy = new TypeOutline(ClassFile.header(bytecode), bytecode);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> results = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
          results.add(
              executor.submit(() -> {
                start.await();
                return describeMethods(lazy);
              }));
        }
        start.countDown();
        for (Future<List<String>> result : results) {
          assertEquals(expected, result.get(10, TimeUnit.SECONDS));
        }
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static List<String> describeMethods(TypeOutline outline) {
    List<String> methods = new ArrayList<>();
    for (MethodDescription method : outline.getDeclaredMethods()) {
      methods.add(method.getModifiers() + " " + method.getInternalName() + method.getDescriptor());
    }
    return methods;
  }

  private static List<String> describeFields(TypeOutline outline) {
    List<String> fields = new ArrayList<>();
    for (FieldDescription field : outline.getDeclaredFields()) {
      fields.add(field.getModifiers() + " " + field.getName() + " " + field.getDescriptor());
    }
    return fields;
  }

  private static byte[] bytecodeOf(Class<?> type) throws IOException {
    String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
      assertNotNull(in);
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
