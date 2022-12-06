package datadog.telemetry.dependency

import org.apache.tools.ant.taskdefs.Classloader

import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

class DependencyResolverSpecification extends DepSpecification {

  void 'guess groupId/artifactId from bundleSymbolicName - #jar'() {
    expect:
    knownJarCheck(
      jarName: jar,
      name: name,
      hash: hash,
      version: version)

    where:
    jar                       | name                         | hash                                       | version
    'bson4jackson-2.11.0.jar' | 'de.undercouch:bson4jackson' | '428A23E33D19DACD6E04CA7DD746206849861A95' | '2.11.0'
    'bson-4.2.0.jar'          | 'org.mongodb:bson'           | 'F87C3A90DA4BB1DA6D3A73CA18004545AD2EF06A' | '4.2.0'
  }

  void 'groupId cannot be resolved #jar'() {
    expect:
    knownJarCheck(
      jarName: jar,
      name: name,
      hash: hash,
      version: version)

    where:
    jar                  | name       | hash                                       | version
    'asm-util-9.2.jar'   | 'asm-util' | '9A5AEC2CB852B8BD20DAF5D2CE9174891267FE27' | '9.2'
    'agrona-1.7.2.jar'   | 'agrona'   | '0646535BE30190223DA51F5AACB080DC1F25FFF9' | '1.7.2'
    'caffeine-2.8.5.jar' | 'caffeine' | 'E4CD34260D3AF66A928E6DB1D2BB6807C6136859' | '2.8.5'
  }

  void 'no version in file name'() {
    expect:
    knownJarCheck(
      jarName: 'spring-webmvc.jar',
      name: 'spring-webmvc',
      hash: '5B3B4AAC5C802E31BCC8517EFA9C9818EF625A0A',
      version: '3.0.0.RELEASE'
      )
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
      hash: '4376590587C49AC6DA6935564233F36B092412AE',
      version: '4.12')
  }

  void 'known jar with manifest bundle'() {
    expect:
    knownJarCheck(
      jarName: 'groovy-manifest.jar',
      name: 'groovy',
      hash: '04DF0875A66F111880217FE1C5C59CA877403239',
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
      version: '',
      hash: '1C1C8E5547A54F593B97584D45F3636F479B9498')
  }

  void 'guess artifact name from implementationTitle'() {
    when:
    File jar = getJar("jakarta.inject-2.6.1.jar")

    JarFile file = new JarFile(jar)
    Manifest manifest = file.manifest
    String source = jar.name
    Dependency dep = Dependency.guessFallbackNoPom(manifest, source, new FileInputStream(jar))

    then:
    dep != null
    dep.name == 'jakarta.inject'
    dep.version == '2.6.1'
    dep.hash == '29BBEDD4A914066F24257A78B73249900B33C656'
    dep.source == 'jakarta.inject-2.6.1.jar'
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
    dep.name == 'freemarker-2.3.27-incubating.jar'
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
    dep.hash == 'CA0722D57F25455BA0CFCBDCA2C347941BD22601'
    dep.source == 'hsqldb-2.3.5-jdk6debug.jar'
  }

  void 'try to determine lib name'() throws IOException {
    setup:
    File temp = File.createTempFile('temp', '.zip')

    expect:
    DependencyResolver.extractDependenciesFromJar(temp).isEmpty()

    cleanup:
    temp.delete()
  }

  void 'spring boot dependency'() throws IOException {
    setup:
    org.springframework.boot.loader.jar.JarFile.registerUrlProtocolHandler()

    when:
    String zipPath = Classloader.classLoader.getResource('datadog/telemetry/dependencies/spring-boot-app.jar').path
    URI uri = new URI("jar:file:$zipPath!/BOOT-INF/lib/opentracing-util-0.33.0.jar!/")

    Dependency dep = DependencyResolver.resolve(uri).get(0)

    then:
    dep != null
    dep.name == 'opentracing-util-0.33.0.jar'
    dep.version == '0.33.0'
    dep.hash == '132630F17E198A1748F23CE33597EFDF4A807FB9'
    dep.source == 'opentracing-util-0.33.0.jar'
  }

  void 'fat jar with multiple pom.properties'() throws IOException {
    setup:
    org.springframework.boot.loader.jar.JarFile.registerUrlProtocolHandler()

    when:
    URI uri = Classloader.classLoader.getResource('datadog/telemetry/dependencies/budgetapp.jar').toURI()

    List<Dependency> deps = DependencyResolver.resolve(uri)

    then:
    deps.size() == 105
  }

  void 'fat jar with two pom.properties'() throws IOException {
    setup:
    org.springframework.boot.loader.jar.JarFile.registerUrlProtocolHandler()

    when:
    URI uri = Classloader.classLoader.getResource('datadog/telemetry/dependencies/budgetappreduced.jar').toURI()

    List<Dependency> deps = DependencyResolver.resolve(uri)
    Dependency dep1 = deps.get(0)
    Dependency dep2 = deps.get(1)

    then:
    deps.size() == 2
    dep1.name == 'org.yaml:snakeyaml' || dep2.name == 'org.yaml:snakeyaml'
  }

  void 'fat jar with two pom.properties one of them bad'() throws IOException {
    setup:
    org.springframework.boot.loader.jar.JarFile.registerUrlProtocolHandler()

    when:
    URI uri = Classloader.classLoader.getResource('datadog/telemetry/dependencies/budgetappreducedbadproperties.jar').toURI()

    List<Dependency> deps = DependencyResolver.resolve(uri)
    Dependency dep1 = deps.get(0)

    then:
    deps.size() == 1
    dep1.name == 'org.yaml:snakeyaml'
  }

  void 'known jar from filename cause bad pom.properties'() {
    // this jar has an invalid pom.properties and it should be resolved with its file name
    expect:
    knownJarCheck(
      jarName: 'invalidpomproperties.jar',
      name: 'invalidpomproperties.jar',
      version: '',
      hash: '6438819DAB9C9AC18D8A6922C8A923C2ADAEA85D')
  }

  void 'retrieve manifest attributes'() {
    when:
    Attributes attributes = manifestAttributesFromJar('junit-4.12.jar')

    then:
    attributes != null

    attributes.getValue('implementation-title') == 'JUnit'
    attributes.getValue('implementation-version') == '4.12'
    attributes.getValue('implementation-vendor') == 'JUnit'
    attributes.getValue('built-by') == 'jenkins'
  }

  private static void knownJarCheck(Map opts) {
    File jarFile = getJar(opts['jarName'])
    List<Dependency> deps = DependencyResolver.extractDependenciesFromJar(jarFile)

    assert deps.size() == 1
    Dependency dep = deps.get(0)
    assert dep != null
    assert dep.source == opts['jarName']
    assert dep.name == opts['name']
    assert dep.version == opts['version']
    assert dep.hash == opts['hash']
  }

  private static Attributes manifestAttributesFromJar(String jarName) {
    File jarFile = getJar(jarName)

    Attributes attributes = DependencyResolver.getManifestAttributes(jarFile)
    assert attributes != null
    attributes
  }
}
