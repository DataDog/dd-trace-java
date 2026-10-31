package datadog.trace.instrumentation.jackson_2_16.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonParser216Helper;
import com.fasterxml.jackson.core.json.UTF8StreamJsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.SimpleRemapper;
import org.junit.jupiter.api.Test;

class JsonParser216HelperTest {

  private static final String JACKSON_CORE_PREFIX = "com.fasterxml.jackson.core.";
  private static final String CANONICALIZER =
      "com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizer";
  private static final String UTF8_PARSER = "com.fasterxml.jackson.core.json.UTF8StreamJsonParser";

  @Test
  void reportsInternedFieldNames() throws Exception {
    JsonFactory factory = new JsonFactory().enable(JsonFactory.Feature.INTERN_FIELD_NAMES);
    UTF8StreamJsonParser parser = (UTF8StreamJsonParser) factory.createParser(json());

    assertTrue(JsonParser216Helper.fetchInterner(parser));
  }

  @Test
  void reportsNonInternedFieldNames() throws Exception {
    JsonFactory factory = new JsonFactory().disable(JsonFactory.Feature.INTERN_FIELD_NAMES);
    UTF8StreamJsonParser parser = (UTF8StreamJsonParser) factory.createParser(json());

    assertFalse(JsonParser216Helper.fetchInterner(parser));
  }

  /**
   * Simulates a classpath whose {@code ByteQuadsCanonicalizer} has no {@code _interner} field. The
   * first failure is rethrown so it is reported once; later calls assume interned names.
   */
  @Test
  void rethrowsFirstMissingInternerThenAssumesInterned() throws Exception {
    assertRethrowsOnceThenAssumesInterned(CANONICALIZER, "_interner");
  }

  /** The same for the parser's {@code _symbols} field, which has its own latch. */
  @Test
  void rethrowsFirstMissingSymbolsThenAssumesInterned() throws Exception {
    assertRethrowsOnceThenAssumesInterned(UTF8_PARSER, "_symbols");
  }

  /**
   * The latch state is static, so it is per class loader: a second loader with the same problem
   * must rethrow its own first failure, not inherit the first loader's latch.
   */
  @Test
  void eachClassLoaderRethrowsItsOwnFirstFailure() throws Exception {
    assertRethrowsOnceThenAssumesInterned(CANONICALIZER, "_interner");
    assertRethrowsOnceThenAssumesInterned(CANONICALIZER, "_interner");
  }

  private void assertRethrowsOnceThenAssumesInterned(String className, String missingField)
      throws Exception {
    ClassLoader loader = new MissingFieldClassLoader(className, missingField);
    Object factory = loader.loadClass(JsonFactory.class.getName()).getConstructor().newInstance();
    Object parser =
        factory.getClass().getMethod("createParser", byte[].class).invoke(factory, (Object) json());
    Method fetchInterner =
        loader
            .loadClass(JsonParser216Helper.class.getName())
            .getMethod("fetchInterner", loader.loadClass(UTF8StreamJsonParser.class.getName()));

    InvocationTargetException first =
        assertThrows(InvocationTargetException.class, () -> fetchInterner.invoke(null, parser));
    assertInstanceOf(NoSuchFieldError.class, first.getCause());

    assertTrue((boolean) fetchInterner.invoke(null, parser));
    assertTrue((boolean) fetchInterner.invoke(null, parser));
  }

  private static byte[] json() {
    return "{\"name\":\"value\"}".getBytes(UTF_8);
  }

  /**
   * Loads jackson-core child-first, renaming one field of one class in the bytecode. The class
   * stays self-consistent, but a lookup of the original field name fails with {@link
   * NoSuchFieldError}, like a mixed or repackaged Jackson on the classpath.
   */
  private static final class MissingFieldClassLoader extends ClassLoader {
    private final String className;
    private final String field;

    MissingFieldClassLoader(String className, String field) {
      super(JsonParser216HelperTest.class.getClassLoader());
      this.className = className;
      this.field = field;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith(JACKSON_CORE_PREFIX)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> clazz = findLoadedClass(name);
        if (clazz == null) {
          clazz = define(name);
        }
        if (resolve) {
          resolveClass(clazz);
        }
        return clazz;
      }
    }

    private Class<?> define(String name) throws ClassNotFoundException {
      try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
        if (in == null) {
          throw new ClassNotFoundException(name);
        }
        byte[] bytes = readAll(in);
        if (name.equals(className)) {
          bytes = renameField(bytes);
        }
        return defineClass(name, bytes, 0, bytes.length);
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }

    private byte[] renameField(byte[] bytes) {
      ClassWriter writer = new ClassWriter(0);
      SimpleRemapper remapper =
          new SimpleRemapper(className.replace('.', '/') + "." + field, field + "_renamed");
      new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
      return writer.toByteArray();
    }

    private static byte[] readAll(InputStream in) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }
}
