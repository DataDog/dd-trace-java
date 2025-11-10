package datadog.trace.agent.tooling;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/** Accesses content from the extension, mapping classes and resources as necessary. */
public class ExtensionHandler {

  /** Handler for loading externally built Datadog extensions. */
  public static final ExtensionHandler DATADOG = new ExtensionHandler();

  /** Provides necessary mappings to load externally built Datadog extensions */
  private static final Function<ClassVisitor, ClassVisitor> DATADOG_MAPPING =
      cv ->
          new ClassRemapper(
              cv,
              new Remapper() {
                @Override
                public String map(String internalName) {
                  return MAP_LOGGING.apply(internalName);
                }
              });

  /** Override this to map filenames to alternative entries in the extension. */
  public JarEntry mapEntry(JarFile jar, String file) {
    return jar.getJarEntry(file);
  }

  /** Override this to map URLs to alternative/mapped content in the extension. */
  public URLConnection mapContent(URL url, JarFile jar, JarEntry entry) {
    if (entry.getName().endsWith(".class")) {
      return new ClassMappingConnection(url, jar, entry, DATADOG_MAPPING);
    } else {
      return new JarFileConnection(url, jar, entry);
    }
  }

  /** Provides access to original content from the extension. */
  protected static class JarFileConnection extends URLConnection {
    private final JarFile jar;
    private final JarEntry entry;

    public JarFileConnection(URL url, JarFile jar, JarEntry entry) {
      super(url);

      this.jar = jar;
      this.entry = entry;
    }

    @Override
    public void connect() {
      connected = true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      return jar.getInputStream(entry);
    }

    @Override
    public long getContentLengthLong() {
      return entry.getSize();
    }
  }

  /** Provides access to bytecode mapped from the extension. */
  protected static class ClassMappingConnection extends JarFileConnection {
    private static final DDCache<String, byte[]> BYTECODE_CACHE = DDCaches.newFixedSizeCache(32);

    private final Function<ClassVisitor, ClassVisitor> mapping;

    public ClassMappingConnection(
        URL url, JarFile jar, JarEntry entry, Function<ClassVisitor, ClassVisitor> mapping) {
      super(url, jar, entry);
      this.mapping = mapping;
    }

    @Override
    public InputStream getInputStream() throws IOException {
      try {
        return new ByteArrayInputStream(mapBytecode());
      } catch (UncheckedIOException e) {
        throw e.getCause();
      }
    }

    @Override
    public long getContentLengthLong() {
      try {
        return mapBytecode().length;
      } catch (UncheckedIOException e) {
        return -1;
      }
    }

    private byte[] mapBytecode() {
      return BYTECODE_CACHE.computeIfAbsent(url.getFile(), this::doMapBytecode);
    }

    protected byte[] doMapBytecode(String unused) {
      try (InputStream in = super.getInputStream()) {
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(mapping.apply(cw), 0);
        return cw.toByteArray();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /** Maps logging references in the extension to use the tracer's embedded logger. */
  public static final Function<String, String> MAP_LOGGING =
      new Function<String, String>() {
        // we want to keep this package unchanged so it matches against any unshaded extensions
        // dropped in at runtime; use replace to stop it being transformed by the shadow plugin
        private final String ORG_SLF4J = "org|slf4j|".replace('|', '/');

        @Override
        public String apply(String internalName) {
          if (internalName.equals("java/util/logging/Logger")) {
            return "datadog/trace/bootstrap/PatchLogger";
          }
          if (internalName.startsWith(ORG_SLF4J)) {
            return "datadog/slf4j/" + internalName.substring(10);
          }
          return internalName;
        }
      };
}
