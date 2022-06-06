package datadog.telemetry.dependency;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.io.Closeable;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.jboss.vfs.TempFileProvider;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VirtualFile;
import org.jboss.vfs.VirtualFileAssembly;
import org.junit.After;
import org.junit.Test;

public class DependencyServiceImplTests {

  DependencyServiceImpl depService = new DependencyServiceImpl();
  Closeable assemblyHandle;
  TempFileProvider tempFileProvider;

  @After
  public void teardown() throws IOException {
    if (assemblyHandle != null) {
      assemblyHandle.close();
    }
    if (tempFileProvider != null) {
      tempFileProvider.close();
    }
  }

  private ClassFileTransformer captureTransformer() {
    final ClassFileTransformer[] t = {null};
    Instrumentation instrumentation =
        (Instrumentation)
            Proxy.newProxyInstance(
                DependencyServiceImplTests.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                new InvocationHandler() {
                  @Override
                  public Object invoke(Object proxy, Method method, Object[] args)
                      throws Throwable {
                    if (method.getName().equals("addTransformer")) {
                      t[0] = (ClassFileTransformer) args[0];
                    } else {
                      throw new UnsupportedOperationException();
                    }
                    return null;
                  }
                });

    depService.installOn(instrumentation);
    assert t[0] != null;
    return t[0];
  }

  private Dependency identifyDependency(ClassFileTransformer t, String url)
      throws MalformedURLException, IllegalClassFormatException {
    VirtualFile virtualGroovyJar = VFS.getChild(url);
    URL groovyJarURL = virtualGroovyJar.toURL();

    CodeSource codeSource = new CodeSource(groovyJarURL, (Certificate[]) null);
    ProtectionDomain domain = new ProtectionDomain(codeSource, null);
    t.transform(getClass().getClassLoader(), "class.name", Object.class, domain, new byte[0]);
    Collection<Dependency> deps = depService.determineNewDependencies();

    if (deps.isEmpty()) {
      return null;
    }

    return deps.iterator().next();
  }

  @Test
  public void jboss_vfs_url() throws IOException, IllegalClassFormatException {
    ClassFileTransformer t = captureTransformer();

    VirtualFileAssembly assembly = new VirtualFileAssembly();
    VirtualFile assemblyLocation = VFS.getChild("assembly.jar");
    assemblyHandle = VFS.mountAssembly(assembly, assemblyLocation);

    VirtualFile virtualDir =
        VFS.getChild(
            ClassLoader.getSystemClassLoader()
                .getResource("datadog/telemetry/dependencies/")
                .getPath());
    assembly.add("/groovy.jar", virtualDir.getChild("groovy-manifest.jar"));

    Dependency dep = identifyDependency(t, "assembly.jar/groovy.jar");
    assertThat(dep, notNullValue());

    assertThat(
        dep,
        allOf(
            hasProperty("name", equalTo("groovy")),
            hasProperty("version", equalTo("2.4.12")),
            hasProperty("source", equalTo("groovy-manifest.jar"))));
  }

  @Test
  public void jboss_vfs_zip_url() throws IOException, IllegalClassFormatException {
    ClassFileTransformer t = captureTransformer();

    VirtualFile zipFile =
        VFS.getChild(
            ClassLoader.getSystemClassLoader()
                .getResource("datadog/telemetry/dependencies/junit.zip")
                .getPath());
    VirtualFile mountPoint = VFS.getChild("foo.zip");
    tempFileProvider = TempFileProvider.create("test", new ScheduledThreadPoolExecutor(2));
    assemblyHandle = VFS.mountZip(zipFile, mountPoint, tempFileProvider);

    Dependency dep = identifyDependency(t, "foo.zip/junit-4.12.jar");
    assertThat(dep, notNullValue());

    assertThat(
        dep,
        allOf(
            hasProperty("name", equalTo("junit")),
            hasProperty("version", equalTo("4.12")),
            hasProperty("source", equalTo("junit-4.12.jar"))));
  }

  @Test
  public void jboss_nonexistent_file() throws IOException, IllegalClassFormatException {
    ClassFileTransformer t = captureTransformer();

    VirtualFileAssembly assembly = new VirtualFileAssembly();
    VirtualFile assemblyLocation = VFS.getChild("assembly.jar");
    assemblyHandle = VFS.mountAssembly(assembly, assemblyLocation);

    Dependency dep = identifyDependency(t, "assembly.jar/nonexistent-1.2.3.jar");
    assertThat(dep, nullValue());
  }
}
