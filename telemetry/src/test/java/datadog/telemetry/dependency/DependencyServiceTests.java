package datadog.telemetry.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.Closeable;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class DependencyServiceTests {
  DependencyService depService = new DependencyService();
  Closeable assemblyHandle;
  TempFileProvider tempFileProvider;

  @AfterEach
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
                DependencyServiceTests.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("addTransformer")) {
                    t[0] = (ClassFileTransformer) args[0];
                  } else {
                    throw new UnsupportedOperationException();
                  }
                  return null;
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
    depService.run(); // for test enforce instant dependency resolution
    Collection<Dependency> deps = depService.drainDeterminedDependencies();

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
    assertNotNull(dep);

    // XXX: This should be `groovy` instead of `groovy-manifest` (ideally). However, we only
    // have the bundle symbolic name with `groovy` and it is not always reliable.
    assertEquals("groovy-manifest", dep.name);
    assertEquals("2.4.12", dep.version);
    assertEquals("groovy-manifest.jar", dep.source);
    assertEquals("04DF0875A66F111880217FE1C5C59CA877403239", dep.hash);
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
    assertNotNull(dep);

    assertEquals("junit", dep.name);
    assertEquals("4.12", dep.version);
    assertEquals("junit-4.12.jar", dep.source);
    assertEquals("4376590587C49AC6DA6935564233F36B092412AE", dep.hash);
  }

  @Test
  public void jboss_nonexistent_file() throws IOException, IllegalClassFormatException {
    ClassFileTransformer t = captureTransformer();

    VirtualFileAssembly assembly = new VirtualFileAssembly();
    VirtualFile assemblyLocation = VFS.getChild("assembly.jar");
    assemblyHandle = VFS.mountAssembly(assembly, assemblyLocation);

    Dependency dep = identifyDependency(t, "assembly.jar/nonexistent-1.2.3.jar");
    assertNull(dep);
  }
}
