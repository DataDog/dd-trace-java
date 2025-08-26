package datadog.trace.agent.tooling.muzzle;

import datadog.config.util.Strings;
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
  private final Map<String, URL> classResources = new HashMap<>();

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
  public URL getResource(String name) {
    URL classResource = classResources.get(name);
    if (classResource == null) {
      return super.getResource(name);
    }
    return classResource;
  }

  public void addClass(Class<?> existingClass) throws IOException {
    String name = existingClass.getName();
    String resourceName = Strings.getResourceName(name);
    URL classResource = existingClass.getResource("/" + resourceName);
    classResources.put(resourceName, classResource);
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
  }
}
