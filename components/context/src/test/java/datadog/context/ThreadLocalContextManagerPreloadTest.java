package datadog.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import org.tabletest.junit.TableTest;

class ThreadLocalContextManagerPreloadTest {
  @TableTest({
    "Scenario               | checkpointFirst",
    "checkpoint before swap | true           ",
    "swap before checkpoint | false          "
  })
  void checkpointAndSwapDoNotLoadClasses(boolean checkpointFirst) throws Exception {
    URL[] classpath = {
      Context.class.getProtectionDomain().getCodeSource().getLocation(),
      getClass().getProtectionDomain().getCodeSource().getLocation()
    };
    try (PreloadClassLoader loader = new PreloadClassLoader(classpath)) {
      Runnable operations =
          (Runnable)
              loader
                  .loadClass(CheckpointAndSwap.class.getName())
                  .getConstructor(boolean.class)
                  .newInstance(checkpointFirst);
      loader.loadingAllowed = false;
      operations.run();
    }
  }

  private static final class PreloadClassLoader extends URLClassLoader {
    private boolean loadingAllowed = true;

    PreloadClassLoader(URL[] classpath) {
      super(classpath, ThreadLocalContextManagerPreloadTest.class.getClassLoader());
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve)
        throws ClassNotFoundException {
      if (!name.startsWith("datadog.context.")) {
        return super.loadClass(name, resolve);
      }
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) {
        if (!loadingAllowed) {
          throw new AssertionError("Class loaded during checkpoint/swap: " + name);
        }
        loaded = findClass(name);
      }
      if (resolve) {
        resolveClass(loaded);
      }
      return loaded;
    }
  }

  /** Loaded in isolation so other tests cannot preload the manager's helpers. */
  public static final class CheckpointAndSwap implements Runnable {
    private final Context root = Context.current();
    private final Context context = root.with(ContextKey.named("preload-test"), "value");
    private final boolean checkpointFirst;

    public CheckpointAndSwap(boolean checkpointFirst) {
      this.checkpointFirst = checkpointFirst;
    }

    @Override
    public void run() {
      Context.setCaptureEnabled(false);
      if (checkpointFirst) {
        ContextHandle checkpoint = root.checkpoint();
        context.swap();
        checkpoint.swap();
      } else {
        ContextHandle previous = context.swap();
        previous.swap();
      }
      assertSame(root, Context.current());
      assertFalse(Context.isCaptureEnabled());

      ContextHandle previous = context.swap();
      assertTrue(Context.isCaptureEnabled());
      assertSame(context, context.checkpoint());
      context.swap();

      Context.setCaptureEnabled(false);
      ContextHandle checkpoint = context.checkpoint();
      ContextHandle suspended = previous.swap();
      assertSame(root, Context.current());
      suspended.swap();
      checkpoint.swap();
      assertSame(context, Context.current());
      assertFalse(Context.isCaptureEnabled());

      root.swap();
      assertTrue(Context.isCaptureEnabled());
    }
  }
}
