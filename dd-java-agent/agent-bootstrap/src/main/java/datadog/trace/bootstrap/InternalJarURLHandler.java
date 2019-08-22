package datadog.trace.bootstrap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.NoSuchFileException;
import java.security.Permission;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class InternalJarURLHandler extends URLStreamHandler {
  private final Set<String> availableFiles = new HashSet<>();
  private final Map<String, byte[]> filenameToBytes = new HashMap<>();
  private final String jarFilename;
  private final ClassLoader classloaderForJarResource;

  InternalJarURLHandler(final String jarFilename, final ClassLoader classloaderForJarResource) {
    this.jarFilename = jarFilename;
    this.classloaderForJarResource = classloaderForJarResource;

    // "/" is used as the default url of the jar
    // This is called by the SecureClassLoader trying to obtain permissions
    filenameToBytes.put("/", new byte[] {});

    final InputStream jarStream =
        jarFilename == null ? null : classloaderForJarResource.getResourceAsStream(jarFilename);

    if (jarStream != null) {
      try (final JarInputStream inputStream = new JarInputStream(jarStream)) {
        JarEntry entry = inputStream.getNextJarEntry();

        while (entry != null) {
          availableFiles.add("/" + entry.getName());

          entry = inputStream.getNextJarEntry();
        }
      } catch (final IOException e) {
        log.error("Unable to read internal jar", e);
      }
    } else {
      log.error("Internal jar not found");
    }
  }

  @Override
  protected URLConnection openConnection(final URL url) throws IOException {
    final String filename = url.getFile();

    byte[] bytes = null;

    if (filenameToBytes.containsKey(filename)) {
      bytes = filenameToBytes.get(filename);
    } else if (availableFiles.contains(filename)) {
      final InputStream jarStream =
          jarFilename == null ? null : classloaderForJarResource.getResourceAsStream(jarFilename);

      if (jarStream != null) {
        try (final JarInputStream inputStream = new JarInputStream(jarStream)) {
          JarEntry entry = inputStream.getNextJarEntry();

          while (entry != null) {
            if (filename.equals("/" + entry.getName())) {
              bytes = getBytes(inputStream);
              filenameToBytes.put(filename, bytes);
              break;
            }

            entry = inputStream.getNextJarEntry();
          }
        } catch (final IOException e) {
          log.error("Unable to read internal jar", e);
        }
      }
    }

    if (bytes == null) {
      throw new NoSuchFileException(url.getFile(), null, url.getFile() + " not in internal jar");
    }

    return new InternalJarURLConnection(url, bytes);
  }

  /**
   * Standard "copy InputStream to byte[]" implementation using a ByteArrayOutputStream
   *
   * <p>IOUtils.toByteArray() or Java 9's InputStream.readAllBytes() could be replacements if they
   * were available
   *
   * <p>This can be optimized using the JarEntry's size(), but its not always available
   *
   * @param inputStream stream to read
   * @return a byte[] from the inputstream
   */
  private static byte[] getBytes(final InputStream inputStream) throws IOException {
    final byte[] buffer = new byte[4096];

    int bytesRead = inputStream.read(buffer, 0, buffer.length);
    try (final ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      while (bytesRead != -1) {
        outputStream.write(buffer, 0, bytesRead);

        bytesRead = inputStream.read(buffer, 0, buffer.length);
      }

      return outputStream.toByteArray();
    }
  }

  private static class InternalJarURLConnection extends URLConnection {
    private final byte[] bytes;

    private InternalJarURLConnection(final URL url, final byte[] bytes) {
      super(url);
      this.bytes = bytes;
    }

    @Override
    public void connect() throws IOException {
      connected = true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public Permission getPermission() {
      // No permissions needed because all classes are in memory
      return null;
    }
  }
}
