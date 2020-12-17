package datadog.smoketest.osgi.app;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

public class IsolatingClassLoader extends ClassLoader {
  @Override
  protected Class<?> loadClass(final String name, final boolean resolve)
      throws ClassNotFoundException {
    if (name.contains("datadog")) {
      throw new ClassNotFoundException("Unexpected request for " + name);
    }
    return super.loadClass(name, resolve);
  }

  @Override
  public URL getResource(final String name) {
    if (name.contains("datadog")) {
      return null;
    }
    return super.getResource(name);
  }

  @Override
  public Enumeration<URL> getResources(final String name) throws IOException {
    if (name.contains("datadog")) {
      return Collections.emptyEnumeration();
    }
    return super.getResources(name);
  }
}
