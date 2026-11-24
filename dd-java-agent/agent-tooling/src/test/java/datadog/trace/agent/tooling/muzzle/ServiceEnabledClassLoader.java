package datadog.trace.agent.tooling.muzzle;

import static java.util.Collections.enumeration;
import static java.util.Collections.singletonList;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.Enumeration;

public class ServiceEnabledClassLoader extends AddableClassLoader {
  private static final String PREFIX = "META-INF/services/";
  private final Class<?> serviceClass;
  private final String serviceResource;
  private final File serviceRegistry;

  protected ServiceEnabledClassLoader(Class<?> serviceClass, Class<?>... inheritedClasses)
      throws IOException {
    super(inheritedClasses);
    super.addDelegateClass(serviceClass);
    this.serviceClass = serviceClass;
    serviceResource = PREFIX + serviceClass.getName();
    serviceRegistry = File.createTempFile(serviceClass.getSimpleName() + "-registry.", ".txt");
    serviceRegistry.deleteOnExit();
    serviceRegistry.createNewFile();
    for (Class<?> clazz : inheritedClasses) {
      registerClass(clazz);
    }
  }

  @Override
  public void addClass(Class<?> existingClass) throws IOException {
    super.addClass(existingClass);
    registerClass(existingClass);
  }

  private void registerClass(Class<?> existingClass) throws IOException {
    if (serviceClass != existingClass
        && !Modifier.isAbstract(existingClass.getModifiers())
        && serviceClass.isAssignableFrom(existingClass)) {
      FileWriter fw = new FileWriter(serviceRegistry, true);
      BufferedWriter bw = new BufferedWriter(fw);
      bw.write(existingClass.getName());
      bw.newLine();
      bw.close();
    }
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    if (!serviceResource.equals(name)) {
      return super.getResources(name);
    }
    return enumeration(singletonList(serviceRegistry.toURI().toURL()));
  }
}
