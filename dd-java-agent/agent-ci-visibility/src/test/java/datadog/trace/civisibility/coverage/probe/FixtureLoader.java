package datadog.trace.civisibility.coverage.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

/** Defines classes of the given packages, instrumented by probes or by the given instrumenter. */
final class FixtureLoader extends ClassLoader {
  private final Function<byte[], byte[]> instrumenter;
  private final String[] packages;

  FixtureLoader(Function<byte[], byte[]> instrumenter, String... packages) {
    super(FixtureLoader.class.getClassLoader());
    this.instrumenter = instrumenter;
    this.packages = packages;
  }

  private boolean isInstrumented(String name) {
    for (String prefix : packages) {
      if (name.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (!isInstrumented(name)) {
      return super.loadClass(name, resolve);
    }
    synchronized (getClassLoadingLock(name)) {
      Class<?> loaded = findLoadedClass(name);
      if (loaded != null) {
        return loaded;
      }
      byte[] original = read(name);
      byte[] bytes =
          instrumenter != null
              ? instrumenter.apply(original)
              : ProbeClassTransformer.instrument(original, this, true);
      if (bytes == null) {
        bytes = original;
      }
      return defineClass(name, bytes, 0, bytes.length);
    }
  }

  private byte[] read(String name) throws ClassNotFoundException {
    try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
      if (in == null) {
        throw new ClassNotFoundException(name);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int read; (read = in.read(buffer)) != -1; ) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
  }
}
