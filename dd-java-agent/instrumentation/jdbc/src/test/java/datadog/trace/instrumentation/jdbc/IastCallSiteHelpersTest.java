package datadog.trace.instrumentation.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.CallSites;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The helpers a call site injects must include every module class they depend on, such as the
 * anonymous {@code ClassLatch} in {@link JDBCDecorator}'s static initializer. Otherwise that class
 * is missing from any class loader the call site injects into without the JDBC module, and
 * initializing {@link JDBCDecorator} there fails for good.
 */
class IastCallSiteHelpersTest {
  private static final String PACKAGE = JDBCDecorator.class.getPackage().getName() + '.';

  static Stream<CallSites> callSites() {
    return Stream.of(new IastConnectionCallSites(), new IastStatementCallSites());
  }

  @ParameterizedTest
  @MethodSource("callSites")
  void declaredHelpersAloneInitializeJdbcDecorator(CallSites callSites) {
    Set<String> helpers = helpersOf(callSites);
    assertTrue(helpers.contains(JDBCDecorator.class.getName()), () -> "helpers: " + helpers);

    ClassLoader loader = new HelpersOnlyClassLoader(helpers, getClass().getClassLoader());

    assertDoesNotThrow(
        () -> Class.forName(JDBCDecorator.class.getName(), true, loader),
        () -> "JDBCDecorator needs a class that is not in the call-site helpers " + helpers);
  }

  private static Set<String> helpersOf(CallSites callSites) {
    Set<String> helpers = new HashSet<>();
    callSites.accept(
        new CallSites.Container() {
          @Override
          public void addAdvice(
              byte kind, String owner, String method, String descriptor, CallSiteAdvice advice) {}

          @Override
          public void addHelpers(String... helperClassNames) {
            helpers.addAll(Arrays.asList(helperClassNames));
          }
        });
    return helpers;
  }

  /**
   * Defines the helpers itself, as helper injection does, and hides every other class of the JDBC
   * instrumentation package, as an application class loader the JDBC module never reached would.
   */
  private static final class HelpersOnlyClassLoader extends ClassLoader {
    private final Collection<String> helpers;

    HelpersOnlyClassLoader(Collection<String> helpers, ClassLoader parent) {
      super(parent);
      this.helpers = helpers;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith(PACKAGE)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          if (!helpers.contains(name)) {
            throw new ClassNotFoundException(name);
          }
          byte[] bytes = bytesOf(name);
          loaded = defineClass(name, bytes, 0, bytes.length);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }

    private byte[] bytesOf(String name) throws ClassNotFoundException {
      String resource = name.replace('.', '/') + ".class";
      try (InputStream in = getParent().getResourceAsStream(resource)) {
        if (in == null) {
          throw new ClassNotFoundException(name);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1; ) {
          out.write(buffer, 0, n);
        }
        return out.toByteArray();
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
  }
}
