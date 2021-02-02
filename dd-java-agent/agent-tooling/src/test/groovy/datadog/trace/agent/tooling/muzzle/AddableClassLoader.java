package datadog.trace.agent.tooling.muzzle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.SecureClassLoader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AddableClassLoader extends SecureClassLoader {

  private final Set<String> toDelegate = new HashSet<>();
  private final Map<String, byte[]> classBytes = new HashMap<>();

  protected AddableClassLoader(Class<?>... inheritedClasses) {
    super(null); // delegate to bootstrap
    for (Class<?> clazz : inheritedClasses) {
      addDelegateClass(clazz);
    }
  }

  protected void addDelegateClass(Class<?> delegate) {
    toDelegate.add(delegate.getName());
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (toDelegate.contains(name)) {
      return getSystemClassLoader().loadClass(name);
    }
    return super.loadClass(name, resolve);
  }

  @Override
  public InputStream getResourceAsStream(String name) {
    byte[] bytes = classBytes.get(name);
    if (bytes == null) {
      return super.getResourceAsStream(name);
    }
    return new ByteArrayInputStream(bytes);
  }

  public void addClass(Class<?> existingClass) throws IOException {
    String name = existingClass.getName();
    String resourceName = name.replace('.', '/') + ".class";
    URL classResource = existingClass.getResource("/" + resourceName);
    InputStream classStream = classResource.openStream();
    assert classStream != null;
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream(classStream.available());
    final byte[] buffer = new byte[classStream.available()];
    int n;
    while (-1 != (n = classStream.read(buffer))) {
      outputStream.write(buffer, 0, n);
    }
    byte[] bytes = outputStream.toByteArray();
    defineClass(name, bytes, 0, bytes.length);
    classBytes.put(resourceName, bytes);
  }
}
