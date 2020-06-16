package datadog.trace.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.NoSuchFileException;
import java.security.Permission;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InternalJarURLHandler extends URLStreamHandler {
  private final Map<String, JarEntry> filenameToEntry = new HashMap<>();
  private JarFile bootstrapJarFile;

  InternalJarURLHandler(final String internalJarFileName, final URL bootstrapJarLocation) {
    final String filePrefix = internalJarFileName + "/";

    try {
      if (bootstrapJarLocation != null) {
        bootstrapJarFile = new JarFile(new File(bootstrapJarLocation.toURI()), false);
        final Enumeration<JarEntry> entries = bootstrapJarFile.entries();
        while (entries.hasMoreElements()) {
          final JarEntry entry = entries.nextElement();

          if (!entry.isDirectory() && entry.getName().startsWith(filePrefix)) {
            String name = entry.getName();
            // remove data suffix
            int end = name.endsWith(".classdata") ? name.length() - 4 : name.length();
            filenameToEntry.put(name.substring(internalJarFileName.length(), end), entry);
          }
        }
      }
    } catch (final URISyntaxException | IOException e) {
      log.error("Unable to read internal jar", e);
    }

    if (filenameToEntry.isEmpty()) {
      log.warn("No internal jar entries found");
    }
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
    final JarEntry entry = filenameToEntry.get(filename);
    if (null != entry) {
      return new InternalJarURLConnection(url, bootstrapJarFile.getInputStream(entry));
    } else {
      throw new NoSuchFileException(url.getFile(), null, url.getFile() + " not in internal jar");
    }
  }

  private static class InternalJarURLConnection extends URLConnection {
    private final InputStream inputStream;

    private InternalJarURLConnection(final URL url, final InputStream inputStream) {
      super(url);
      this.inputStream = inputStream;
    }

    @Override
    public void connect() throws IOException {
      connected = true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return inputStream;
    }

    @Override
    public Permission getPermission() {
      // No permissions needed because all classes are in memory
      return null;
    }
  }
}
