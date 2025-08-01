package datadog.trace.agent.test;

import java.io.IOException;
import java.util.List;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.spockframework.mock.IMockInvocation;
import org.spockframework.mock.TooManyInvocationsError;

public final class SpockExtension
    implements BeforeAllCallback,
        AfterAllCallback,
        BeforeEachCallback,
        AfterEachCallback,
        TestExecutionExceptionHandler {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create("dd", "spock", "loader");

  @Override
  public void beforeAll(ExtensionContext ctx) {
    Class<?> testClass = ctx.getRequiredTestClass();
    BootstrapClasspathSetup.assertNoBootstrapClassesInTestClass(testClass);

    InstrumentationClassLoader custom =
        new InstrumentationClassLoader(testClass.getClassLoader(), testClass.getName());
    ctx.getStore(NAMESPACE).put("loader", custom);
  }

  @Override
  public void afterAll(ExtensionContext ctx) {
    // nothing to clean‑up – garbage collector will take care of loader
  }

  @Override
  public void beforeEach(ExtensionContext ctx) {
    ClassLoader custom = ctx.getStore(NAMESPACE).get("loader", ClassLoader.class);
    ctx.getStore(NAMESPACE).put("prevTCCL", Thread.currentThread().getContextClassLoader());
    Thread.currentThread().setContextClassLoader(custom);
  }

  @Override
  public void afterEach(ExtensionContext ctx) {
    ClassLoader prev = ctx.getStore(NAMESPACE).remove("prevTCCL", ClassLoader.class);
    if (prev != null) {
      Thread.currentThread().setContextClassLoader(prev);
    }
  }

  @Override
  public void handleTestExecutionException(ExtensionContext ctx, Throwable ex) throws Throwable {
    if (ex instanceof TooManyInvocationsError) {
      fixTooManyInvocationsError((TooManyInvocationsError) ex);
      throw ex; // re‑throw so JUnit still marks the test as failed.
    }
    throw ex;
  }

  static void fixTooManyInvocationsError(final TooManyInvocationsError error) {
    final List<IMockInvocation> accepted = error.getAcceptedInvocations();
    for (final IMockInvocation invocation : accepted) {
      try {
        invocation.toString();
      } catch (final Throwable t) {
        final List<Object> args = invocation.getArguments();
        for (int i = 0; i < args.size(); i++) {
          final Object arg = args.get(i);
          if (arg instanceof AssertionError) {
            args.set(
                i,
                new AssertionError(
                    "'"
                        + arg.getClass().getName()
                        + "' hidden due to '"
                        + t.getClass().getName()
                        + "'",
                    t));
          }
        }
      }
    }
  }

  /**
   * Class‑loader that <em>shadows</em> the test class, so that any re‑definitions via ByteBuddy
   * operate on a loader‑private copy instead of the one used by the build toolʼs class‑path
   * scanning. Delegation order = child‑first for the testʼs own package, parent‑first otherwise.
   */
  private static class InstrumentationClassLoader extends ClassLoader {
    private final ClassLoader parent;
    private final String shadowPrefix;

    InstrumentationClassLoader(ClassLoader parent, String shadowPrefix) {
      super(parent);
      this.parent = parent;
      this.shadowPrefix = shadowPrefix;
    }

    /** Inject the bytes of {@code clazz} into <b>this</b> loader, producing a shadow copy. */
    Class<?> shadow(Class<?> clazz) throws IOException {
      Class<?> loaded = findLoadedClass(clazz.getName());
      if (loaded != null && loaded.getClassLoader() == this) {
        return loaded;
      }
      byte[] classBytes =
          ClassFileLocator.ForClassLoader.of(clazz.getClassLoader())
              .locate(clazz.getName())
              .resolve();
      return defineClass(clazz.getName(), classBytes, 0, classBytes.length);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c != null) {
          return c;
        }
        if (name.startsWith(shadowPrefix)) {
          try {
            Class<?> shadowed = shadow(parent.loadClass(name));
            if (resolve) {
              resolveClass(shadowed);
            }
            return shadowed;
          } catch (Exception ignored) {
            // fall‑back to parent below
          }
        }
        return super.loadClass(name, resolve);
      }
    }
  }
}
