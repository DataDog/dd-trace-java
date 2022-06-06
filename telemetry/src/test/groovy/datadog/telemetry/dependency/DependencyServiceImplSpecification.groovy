package datadog.telemetry.dependency

import datadog.trace.test.util.DDSpecification
import org.apache.tools.ant.taskdefs.Classloader

import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.IllegalClassFormatException
import java.lang.instrument.Instrumentation
import java.security.CodeSigner
import java.security.CodeSource
import java.security.ProtectionDomain
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

import static org.hamcrest.Matchers.is
import static org.junit.Assert.assertThat

class DependencyServiceImplSpecification extends DDSpecification {
  DependencyServiceImpl depService = new DependencyServiceImpl()

  void 'no uris pushed should result in empty list'() {
    when:
    def dependencies = depService.determineNewDependencies()

    then:
    dependencies.isEmpty()
  }

  void 'null uri results in NPE'() {
    when:
    depService.addURL(null)

    then:
    thrown NullPointerException
  }

  void 'class files are ignored as dependencies'() {
    when:
    depService.addURL(new URL('file:///tmp/toto.class'))

    then:
    depService.determineNewDependencies().isEmpty()
  }

  void 'add missing jar url dependency'() throws URISyntaxException {
    when:
    // this URI comes from a spring-boot application
    URL url = new URL('jar:file:/tmp//spring-petclinic-2.1.0.BUILD-SNAPSHOT.jar!/BOOT-INF/lib/spring-boot-2.1.0.BUILD-SNAPSHOT.jar!//')
    depService.addURL(url)

    then:
    depService.determineNewDependencies().isEmpty()
  }

  void 'invalid jar names are ignored'() {
    when:
    depService.addURL(new File(".zip").toURL())

    then:
    depService.determineNewDependencies().isEmpty()
  }

  void 'try to determine lib name'() throws IOException {
    setup:
    File temp = File.createTempFile('temp', '.zip')

    expect:
    depService.identifyLibrary(temp) == null

    cleanup:
    temp.delete()
  }

  void 'get manifest attributes'() {
    when:
    Attributes attributes = manifestAttributesFromJar('junit-4.12.jar')

    then:
    attributes != null

    attributes.getValue('implementation-title') == 'JUnit'
    attributes.getValue('implementation-version') ==  '4.12'
    attributes.getValue('implementation-vendor') == 'JUnit'
    attributes.getValue('built-by') == 'jenkins'
  }

  private static Attributes manifestAttributesFromJar(String jarName){
    File jarFile = getJar(jarName)

    Attributes attributes = DependencyServiceImpl.getManifestAttributes(jarFile)
    assert attributes != null
    attributes
  }

  void 'known jar with maven pom'() {
    expect:
    knownJarCheck(
      jarName: 'commons-logging-1.2.jar',
      name: 'commons-logging:commons-logging',
      version: '1.2')
  }

  void 'known jar with manifest implementation'() {
    expect:
    knownJarCheck(
      jarName: 'junit-4.12.jar',
      name: 'junit',
      version: '4.12')
  }

  void 'known jar with manifest bundle'() {
    expect:
    knownJarCheck(
      jarName: 'groovy-manifest.jar',
      name: 'groovy',
      version: '2.4.12')
  }

  void 'known jar from filename'() {
    // this jar has a manifest but should be resolved with its file name
    expect:
    knownJarCheck(
      jarName: 'multiverse-core-0.7.0.jar',
      name: 'org.multiverse:multiverse-core',
      version: '0.7.0')
  }

  void 'no manifest info bad filename'() {
    // If no manifest info and no suitable file name - calculate sha1 hash
    knownJarCheck(
      jarName: 'groovy-no-manifest-info.jar',
      name: 'groovy-no-manifest-info.jar',
      expectedVersion: '',
      expectedInfo: '1C1C8E5547A54F593B97584D45F3636F479B9498')
  }

  void 'build dependency set from known jar'() {
    when:
    File junitJar = getJar('junit-4.12.jar')

    depService.addURL(new File(junitJar.getAbsolutePath()).toURL())
    def set = depService.determineNewDependencies() as Set

    then:
    assertThat(set.size(), is(1))

    assertThat(set.first().name, is('junit'))
    assertThat(set.first().version, is('4.12'))
  }

  void 'guess artifact name from jar'() {
    when:
    File jar = getJar("freemarker-2.3.27-incubating.jar")

    JarFile file = new JarFile(jar)
    Manifest manifest = file.manifest
    String source = jar.name
    Dependency dep = Dependency.guessFallbackNoPom(manifest, source, new FileInputStream(jar))

    then:
    dep != null
    dep.name == 'freemarker'
    dep.version == '2.3.27'
    dep.hash == '3F476E5A287F5CE4951E2F61F3287C122C558067'
    dep.source == 'freemarker-2.3.27-incubating.jar'
  }

  void 'guess artifact name from jar variant'() throws IOException {
    when:
    File jar = getJar('hsqldb-2.3.5-jdk6debug.jar')
    JarFile file = new JarFile(jar)
    Manifest manifest = file.manifest
    String source = jar.name
    Dependency dep = Dependency.guessFallbackNoPom(manifest, source, new FileInputStream(jar))

    then:
    dep != null
    dep.name == 'hsqldb'
    dep.version == '2.3.5'
    dep.hash == null
    dep.source == 'hsqldb-2.3.5-jdk6debug.jar'
  }

  void 'spring boot dependency'() throws IOException {
    setup:
    org.springframework.boot.loader.jar.JarFile.registerUrlProtocolHandler()

    when:
    String zipPath = Classloader.classLoader.getResource('datadog/telemetry/dependencies/spring-boot-app.jar').path
    URI uri = new URI("jar:file:$zipPath!/BOOT-INF/lib/opentracing-util-0.33.0.jar!/")

    Dependency dep = depService.identifyLibrary(uri)

    then:
    dep != null
    dep.name == 'opentracing-util'
    dep.version == '0.33.0'
    dep.hash == '132630F17E198A1748F23CE33597EFDF4A807FB9'
    dep.source == 'opentracing-util-0.33.0.jar'
  }

  void 'transformer invalid code source'() throws IllegalClassFormatException, MalformedURLException {
    Instrumentation instrumentation = Mock()
    ClassFileTransformer t

    when:
    depService.installOn(instrumentation)

    then:
    1 * instrumentation.addTransformer(_) >> { t = it[0] }
    t != null


    when:
    // null protection domain
    t.transform(null, null, null, null, null)

    then:
    depService.determineNewDependencies().isEmpty()

    when:
    // null code source
    ProtectionDomain protectionDomain1 = new ProtectionDomain(null, null, null, null)
    t.transform(null, null, null, protectionDomain1, null)

    then:
    depService.determineNewDependencies().isEmpty()

    when:
    // null code source location
    CodeSource codeSource2 = new CodeSource(null, (CodeSigner[]) null)
    ProtectionDomain protectionDomain2 = new ProtectionDomain(codeSource2, null, null, null)
    t.transform(null, null, null, protectionDomain2, null)

    then:
    codeSource2.location == null
    depService.determineNewDependencies().isEmpty()

    when:
    // null or invalid URI syntax
    URL url3 = new URL('http:// ') // this url is known to not be a valid URI
    CodeSource codeSource3 = new CodeSource(url3, (CodeSigner[]) null)
    ProtectionDomain protectionDomain3 = new ProtectionDomain(codeSource3, null, null, null)
    t.transform(null, null, null, protectionDomain3, null)

    then:
    depService.determineNewDependencies().isEmpty()
  }

  private void knownJarCheck(Map opts) {
    File jarFile = getJar(opts['jarName'])
    Dependency dep = depService.identifyLibrary(jarFile)

    assert dep != null
    assert dep.source == opts['jarName']
    assert dep.name == opts['name']
    assert dep.version == opts['version']
    assert dep.hash == opts['hash']
  }

  private static File getJar(String jarName) {
    String path = ClassLoader.systemClassLoader.getResource("datadog/telemetry/dependencies/$jarName").path
    File jarFile = new File(path)
    assert jarFile.isFile()
    jarFile
  }
}
