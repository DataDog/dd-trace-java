package datadog.telemetry.dependency;

import static datadog.telemetry.dependency.DependencyTestHelper.getJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DependencyServiceTest {

  private final DependencyService depService = new DependencyService();

  @Test
  void noUrisPushedShouldResultInEmptyList() {
    depService.resolveOneDependency();

    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();

    assertTrue(dependencies.isEmpty());
  }

  @Test
  void nullUriResultsInNPE() {
    assertThrows(NullPointerException.class, () -> depService.addURL(null));
  }

  @Test
  void classFilesAreIgnoredAsDependencies() throws MalformedURLException {
    depService.addURL(new URL("file:///tmp/toto.class"));
    depService.resolveOneDependency();

    assertTrue(depService.drainDeterminedDependencies().isEmpty());
  }

  @Test
  void addMissingJarUrlDependency() throws MalformedURLException {
    // this URI comes from a spring-boot application
    URL url =
        new URL(
            "jar:file:/tmp//spring-petclinic-2.1.0.BUILD-SNAPSHOT.jar!/BOOT-INF/lib/spring-boot-2.1.0.BUILD-SNAPSHOT.jar!//");
    depService.addURL(url);
    depService.resolveOneDependency();

    assertTrue(depService.drainDeterminedDependencies().isEmpty());
  }

  @Test
  void invalidJarNamesAreIgnored() throws IOException {
    depService.addURL(new File(".zip").toURI().toURL());
    depService.resolveOneDependency();

    assertTrue(depService.drainDeterminedDependencies().isEmpty());
  }

  @Test
  void buildDependencySetFromKnownJar() throws IOException {
    File junitJar = getJar("junit-4.12.jar");

    depService.addURL(junitJar.toURI().toURL());
    depService.resolveOneDependency();

    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();
    assertEquals(1, dependencies.size());
    Dependency dependency = dependencies.iterator().next();
    assertEquals("junit", dependency.name);
    assertEquals("4.12", dependency.version);
  }

  @Test
  void convertToURIWorksWithASpacedURL() throws MalformedURLException {
    URI uri =
        depService.convertToURI(
            new URL(
                "file:/C:/Program Files/IBM/WebSphere/AppServer_1/plugins/com.ibm.cds_2.1.0.jar"));

    assertEquals(
        "/C:/Program Files/IBM/WebSphere/AppServer_1/plugins/com.ibm.cds_2.1.0.jar", uri.getPath());
  }

  @Test
  void convertToURIWorksWithANestedURL() throws MalformedURLException {
    URI uri =
        depService.convertToURI(
            new URL("jar:file:/Users/test/spring-petclinic.jar!/BOOT-INF/classes!/"));

    assertEquals(
        "file:/Users/test/spring-petclinic.jar!/BOOT-INF/classes!/", uri.getSchemeSpecificPart());
  }

  @Test
  void buildDependencySetFromAFatJar() throws IOException {
    File budgetappJar = getJar("budgetapp.jar");

    depService.addURL(budgetappJar.toURI().toURL());
    depService.resolveOneDependency();

    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();
    assertEquals(105, dependencies.size());
  }

  @Test
  void buildDependencySetFromASmallFatJar() throws IOException {
    File budgetappJar = getJar("budgetappreduced.jar");

    depService.addURL(budgetappJar.toURI().toURL());
    depService.resolveOneDependency();

    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();
    assertEquals(2, dependencies.size());
    Map<String, Dependency> dependenciesByName = dependenciesByName(dependencies);
    assertEquals("3.2.4", dependenciesByName.get("cglib:cglib").version);
    assertEquals("1.17", dependenciesByName.get("org.yaml:snakeyaml").version);
  }

  @Test
  void buildDependencySetFromASmallFatJarWithOneIncorrectPomProperties() throws IOException {
    File budgetappJar = getJar("budgetappreducedbadproperties.jar");

    depService.addURL(budgetappJar.toURI().toURL());
    depService.resolveOneDependency();

    Collection<Dependency> dependencies = depService.drainDeterminedDependencies();
    assertEquals(1, dependencies.size());
    Dependency dependency = dependencies.iterator().next();
    assertEquals("org.yaml:snakeyaml", dependency.name);
    assertEquals("1.17", dependency.version);
  }

  @Test
  void transformerInvalidCodeSource() throws MalformedURLException, IllegalClassFormatException {
    Instrumentation instrumentation = mock(Instrumentation.class);

    depService.installOn(instrumentation);

    ArgumentCaptor<ClassFileTransformer> transformerCaptor =
        ArgumentCaptor.forClass(ClassFileTransformer.class);
    verify(instrumentation).addTransformer(transformerCaptor.capture());
    ClassFileTransformer transformer = transformerCaptor.getValue();
    assertNotNull(transformer);

    // null protection domain
    transformer.transform(null, null, null, null, null);
    depService.resolveOneDependency();
    assertTrue(depService.drainDeterminedDependencies().isEmpty());

    // null code source
    ProtectionDomain protectionDomainWithoutCodeSource =
        new ProtectionDomain(null, null, null, null);
    transformer.transform(null, null, null, protectionDomainWithoutCodeSource, null);
    depService.resolveOneDependency();
    assertTrue(depService.drainDeterminedDependencies().isEmpty());

    // null code source location
    CodeSource codeSourceWithoutLocation = new CodeSource(null, (CodeSigner[]) null);
    ProtectionDomain protectionDomainWithoutLocation =
        new ProtectionDomain(codeSourceWithoutLocation, null, null, null);
    transformer.transform(null, null, null, protectionDomainWithoutLocation, null);
    depService.resolveOneDependency();
    assertNull(codeSourceWithoutLocation.getLocation());
    assertTrue(depService.drainDeterminedDependencies().isEmpty());

    // null or invalid URI syntax
    URL invalidUrl = new URL("http:// "); // this url is known to not be a valid URI
    CodeSource codeSourceWithInvalidUrl = new CodeSource(invalidUrl, (CodeSigner[]) null);
    ProtectionDomain protectionDomainWithInvalidUrl =
        new ProtectionDomain(codeSourceWithInvalidUrl, null, null, null);
    transformer.transform(null, null, null, protectionDomainWithInvalidUrl, null);
    depService.resolveOneDependency();
    assertTrue(depService.drainDeterminedDependencies().isEmpty());
  }

  private static Map<String, Dependency> dependenciesByName(Collection<Dependency> dependencies) {
    Map<String, Dependency> dependenciesByName = new HashMap<>();
    for (Dependency dependency : dependencies) {
      dependenciesByName.put(dependency.name, dependency);
    }
    return dependenciesByName;
  }
}
