package datadog.trace.bootstrap;

import datadog.trace.bootstrap.instrumentation.api.Pair;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.Permission;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InternalJarURLHandler extends URLStreamHandler {

  private static final WeakReference<Pair<String, JarEntry>> NULL = new WeakReference<>(null);

  private final String name;
  private final FileNotInInternalJar notFound;
  private final Map<String, Lock> packages = new HashMap<>();
  private final JarFile bootstrapJarFile;

  private static final ThreadLocal<StringBuilder> JAR_ENTRY_QUERY =
      new ThreadLocal<StringBuilder>() {
        @Override
        protected StringBuilder initialValue() {
          return new StringBuilder(128);
        }
      };

  private WeakReference<Pair<String, JarEntry>> cache = NULL;

  InternalJarURLHandler(final String internalJarFileName, final URL bootstrapJarLocation) {
    this.name = internalJarFileName;
    this.notFound = new FileNotInInternalJar(internalJarFileName);
    final String filePrefix = internalJarFileName + "/";
    JarFile jarFile = null;
    try {
      if (bootstrapJarLocation != null) {
        jarFile = new JarFile(new File(bootstrapJarLocation.toURI()), false);
        final Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
          final JarEntry entry = entries.nextElement();
          String name = entry.getName();
          if (name.startsWith(filePrefix)) {
            if (entry.isDirectory() && !name.contains("/META-INF/")) {
              int prefix = filePrefix.length();
              if (name.length() > prefix) {
                String dir = name.substring(prefix, name.length() - 1);
                String currentPackage = dir.replace('/', '.');
                packages.put(currentPackage, new Lock(currentPackage));
              }
            }
          }
        }
      }
    } catch (final URISyntaxException | IOException e) {
      log.error("Unable to read internal jar", e);
    }

    if (packages.isEmpty()) {
      log.warn("No internal jar entries found");
    }
    this.bootstrapJarFile = jarFile;
  }

  Map<String, Lock> getPackages() {
    return packages;
  }

  Lock getPackageLock(String packageName) {
    return packages.get(packageName);
  }

  @Override
  protected URLConnection openConnection(final URL url) throws IOException {
    final String filename = url.getFile();
    if ("/".equals(filename)) {
      // "/" is used as the default url of the jar
      // This is called by the SecureClassLoader trying to obtain permissions

      // nullInputStream() is not available until Java 11
      return new InternalJarURLConnection(url, new ByteArrayInputStream(new byte[0]));
    }
    // believe it or not, we're going to get called twice for this,
    // and the key will be a new object each time.
    Pair<String, JarEntry> pair = cache.get();
    if (null == pair || !filename.equals(pair.getLeft())) {
      StringBuilder sb = JAR_ENTRY_QUERY.get();
      sb.append(this.name).append(filename);
      if (filename.endsWith(".class")) {
        sb.append("data");
      }
      String classFileName = sb.toString();
      sb.setLength(0);
      JarEntry entry = bootstrapJarFile.getJarEntry(classFileName);
      if (null != entry) {
        pair = Pair.of(filename, entry);
        // this mechanism intentionally does not ensure visibility of this write, because it doesn't
        // matter
        this.cache = new WeakReference<>(pair);
      } else {
        throw notFound;
      }
    } else {
      // hack: just happen to know this only ever happens twice,
      // so dismiss cache after a hit
      this.cache = NULL;
    }
    return new InternalJarURLConnection(url, bootstrapJarFile.getInputStream(pair.getRight()));
  }

  private static class InternalJarURLConnection extends URLConnection {
    private final InputStream inputStream;

    private InternalJarURLConnection(final URL url, final InputStream inputStream) {
      super(url);
      this.inputStream = inputStream;
    }

    @Override
    public void connect() {
      connected = true;
    }

    @Override
    public InputStream getInputStream() {
      return inputStream;
    }

    @Override
    public Permission getPermission() {
      // No permissions needed because all classes are in memory
      return null;
    }
  }

  private static class FileNotInInternalJar extends IOException {

    public FileNotInInternalJar(String jarName) {
      super("class not found in " + jarName);
    }

    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  }

  /**
   * This {@link Lock} allows the class loading code to check if failures to find a class should be
   * delegated to {@code findClass} or if it should fall through to {@code super.loadClass} which is
   * needed for classes that we inject that live in the {@code java.*} package.
   */
  public static final class Lock {
    private final boolean delegateFailureToFindClass;

    public Lock(String packageName) {
      this.delegateFailureToFindClass = !packageName.startsWith("java.");
    }

    public boolean delegateFailureToFindClass() {
      return delegateFailureToFindClass;
    }
  }
}
