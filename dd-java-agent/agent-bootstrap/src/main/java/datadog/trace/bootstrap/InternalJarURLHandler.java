package datadog.trace.bootstrap;

import datadog.trace.api.Pair;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.Permission;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class InternalJarURLHandler extends URLStreamHandler {

  private static final WeakReference<Pair<String, JarEntry>> NULL = new WeakReference<>(null);

  private final String name;
  private final FileNotInInternalJar notFound;
  private final Set<String> packages;
  private final JarFile bootstrapJarFile;

  private WeakReference<Pair<String, JarEntry>> cache = NULL;

  InternalJarURLHandler(String internalJarFileName, Set<String> packages, JarFile jarFile) {
    this.name = internalJarFileName;
    this.notFound = new FileNotInInternalJar(internalJarFileName);
    this.packages = packages;
    this.bootstrapJarFile = jarFile;
  }

  Set<String> getPackages() {
    return packages;
  }

  boolean hasPackage(String packageName) {
    return packages.contains(packageName);
  }

  @Override
  protected URLConnection openConnection(final URL url) throws IOException {
    final String filename = url.getFile();
    if ("/".equals(filename)) {
      // "/" is used as the default url of the jar
      // This is called by the SecureClassLoader trying to obtain permissions

      // nullInputStream() is not available until Java 11
      return new InternalJarURLConnection(url, new ByteArrayInputStream(new byte[0]), 0);
    }
    // believe it or not, we're going to get called twice for this,
    // and the key will be a new object each time.
    Pair<String, JarEntry> pair = cache.get();
    if (null == pair || !filename.equals(pair.getLeft())) {
      String classFileName = this.name + filename + (filename.endsWith(".class") ? "data" : "");
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
    return new InternalJarURLConnection(
        url, bootstrapJarFile.getInputStream(pair.getRight()), (int) pair.getRight().getSize());
  }

  private static final class InternalJarURLConnection extends URLConnection {
    private final InputStream inputStream;
    private final int contentLength;

    private InternalJarURLConnection(URL url, InputStream inputStream, int contentLength) {
      super(url);
      this.inputStream = inputStream;
      this.contentLength = contentLength;
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

    @Override
    public int getContentLength() {
      return contentLength;
    }
  }

  private static final class FileNotInInternalJar extends IOException {

    public FileNotInInternalJar(String jarName) {
      super("class not found in " + jarName);
    }

    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  }
}
