import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** The harness boot-loads its logger; loads a separate application Logback and SLF4J. */
public class LogbackTestClassLoader extends ClassLoader {
  public LogbackTestClassLoader(ClassLoader parent) {
    super(parent);
  }

  @Override
  protected synchronized Class<?> loadClass(String name, boolean resolve)
      throws ClassNotFoundException {
    if (!name.startsWith("ch.qos.logback.")
        && !name.startsWith("org.slf4j.")
        && !name.startsWith("LogbackTestApplication")) {
      return super.loadClass(name, resolve);
    }
    Class<?> loaded = findLoadedClass(name);
    if (loaded == null) {
      byte[] bytes = readClass(name);
      loaded = defineClass(name, bytes, 0, bytes.length);
    }
    if (resolve) {
      resolveClass(loaded);
    }
    return loaded;
  }

  private byte[] readClass(String name) throws ClassNotFoundException {
    try (InputStream input = getResourceAsStream(name.replace('.', '/') + ".class")) {
      if (input == null) {
        throw new ClassNotFoundException(name);
      }
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      for (int read; (read = input.read(buffer)) != -1; ) {
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
  }
}
