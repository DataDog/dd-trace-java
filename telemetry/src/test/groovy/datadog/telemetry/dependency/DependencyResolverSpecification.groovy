package datadog.telemetry.dependency

import spock.lang.TempDir

import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DependencyResolverSpecification extends DepSpecification {

  @TempDir
  File testDir

  void 'resolve from manifest and filename fallbacks'() {
    given: 'a jar with manifest.mf only'
    def attributes = [
      'Bundle-SymbolicName'   : bundleSymbolicName,
      'Bundle-Name'           : bundleName,
      'Bundle-Version'        : bundleVersion,
      'Implementation-Title'  : implementationTitle,
      'Implementation-Version': implementationVersion,
    ]
    File file = prepareJar(filename, attributes)

    when:
    def dependencies = DependencyResolver.resolve(file.toURI())

    then:
    dependencies.size() == 1
    def dep = dependencies[0]
    dep.name == name
    dep.version == version
    dep.source == filename
    dep.hash != null
    !dep.hash.isEmpty()

    where:
    bundleSymbolicName                          | bundleName                      | bundleVersion              | implementationTitle               | implementationVersion | filename                           || name                                        | version
    'org.agrona.core' | 'org.agrona.core' | '1.7.2' | 'Agrona' | '1.7.2' | 'agrona-1.7.2.jar' || 'agrona'                                                                                                                                                      | '1.7.2'
    'org.objectweb.asm.util'                    | 'org.objectweb.asm.util'        | '9.2.0'                    | 'Utilities for ASM'               | '9.2'                 | 'asm-util-9.2.jar'                 || 'asm-util'                                  | '9.2'
    'org.mongodb.bson'                          | 'bson'                          | '4.2.0'                    | null                              | null                  | 'bson-4.2.0.jar'                   || 'org.mongodb:bson'                          | '4.2.0'
    'de.undercouch.bson4jackson'                | 'bson4jackson'                  | '2.11.0'                   | null                              | null                  | 'bson4jackson-2.11.0.jar'          || 'de.undercouch:bson4jackson'                | '2.11.0'
    'com.github.ben-manes.caffeine'             | 'com.github.ben-manes.caffeine' | '2.8.5'                    | null                              | null                  | 'caffeine-2.8.5.jar'               || 'com.github.ben-manes:caffeine'             | '2.8.5'
    'org.apache.commons.logging'                | 'Apache Commons Logging'        | '1.2.0'                    | 'Apache Commons Logging'          | '1.2'                 | 'commons-logging-1.2.jar'          || 'commons-logging'                           | '1.2'
    'org.freemarker.freemarker'                 | 'org.freemarker.freemarker'     | '2.3.27.stable-incubating' | 'FreeMarker'                      | '2.3.27'              | 'freemarker-2.3.27-incubating.jar' || 'org.freemarker:freemarker'                 | '2.3.27-incubating'
    'groovy'                                    | 'Groovy Runtime'                | '2.4.12'                   | null                              | null                  | 'groovy-2.4.12.jar'                || 'groovy'                                    | '2.4.12'
    'groovy'                                    | 'Groovy Runtime'                | '2.4.12'                   | null                              | null                  | 'groovy.jar'                       || 'groovy'                                    | ''
    'org.hsqldb.hsqldb'                         | 'HSQLDB'                        | '2.3.5'                    | 'Standard runtime'                | '2.3.5'               | 'hsqldb-2.3.5-jdk6debug.jar'       || 'org.hsqldb:hsqldb'                         | '2.3.5-jdk6debug'
    'org.glassfish.hk2.external.jakarta.inject' | 'javax.inject:1 as OSGi bundle' | '2.6.1'                    | null                              | null                  | 'jakarta.inject-2.6.1.jar'         || 'org.glassfish.hk2.external:jakarta.inject' | '2.6.1'
    null                                        | null                            | null                       | 'JUnit'                           | '4.12'                | 'junit-4.12.jar'                   || 'junit'                                     | '4.12'
    null                                        | null                            | null                       | 'JUnit'                           | '4.12'                | 'junit.jar'                        || 'junit'                                     | ''
    null                                        | null                            | null                       | null                              | null                  | 'multiverse-core-0.7.0.jar'        || 'multiverse-core'                           | '0.7.0'
    null                                        | null                            | null                       | null                              | null                  | 'multiverse-core.jar'              || 'multiverse-core'                           | ''
    'org.springframework.web.servlet'           | 'Spring Web Servlet'            | '3.0.0.RELEASE'            | 'org.springframework.web.servlet' | '3.0.0.RELEASE'       | 'spring-webmvc.jar'                || 'spring-webmvc'                             | '3.0.0.RELEASE'
    'com.opencsv'                               | 'opencsv'                       | '4.1.0'                    | null                              | null                  | 'opencsv-4.1.jar'                  || 'opencsv'                                   | '4.1'
    'org.liquibase.core'                        | 'liquibase-core'                | '0.0.0.SNAPSHOT'           | null                              | null                  | 'liquibase-core-4.6.2.jar'         || 'liquibase-core'                            | '4.6.2'
    'org.roaringbitmap.RoaringBitmap'           | 'RoaringBitmap'                 | '0.0.1'                    | null                              | null                  | 'RoaringBitmap-0.0.1.jar'          || 'org.roaringbitmap:RoaringBitmap'           | '0.0.1'
    'com.samskivert.jmustache'                  | 'jmustache'                     | '1.14.0'                   | null                              | null                  | 'jmustache-1.14.jar'               || 'com.samskivert:jmustache'                  | '1.14'
  }

  private File prepareJar(final filename, final attributes) {
    File file = new File(testDir, filename)
    ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))
    ZipEntry e = new ZipEntry("META-INF/MANIFEST.MF")
    def manifest = attributes.findAll { it.value != null }.collect { k, v -> "$k: $v" }.join('\n')
    out.putNextEntry(e)
    out.write(manifest.getBytes(Charset.forName('UTF-8')))
    out.closeEntry()
    out.close()
    file
  }

  void 'jar without pom.properties get resolved with hash'() {
    expect:
    knownJarCheck(
      jarName: jar,
      name: name,
      hash: hash,
      version: version)

    /* codenarc-disable */
    where:
    jar                       || name                         | hash                                       | version
    'bson4jackson-2.11.0.jar' || 'de.undercouch:bson4jackson' | '428A23E33D19DACD6E04CA7DD746206849861A95' | '2.11.0'
    'bson-4.2.0.jar'          || 'org.mongodb:bson'           | 'F87C3A90DA4BB1DA6D3A73CA18004545AD2EF06A' | '4.2.0'
    /* codenarc-enable */
  }

  void 'jar with pom.properties is resolved'() {
    expect:
    knownJarCheck(
      jarName: 'commons-logging-1.2.jar',
      name: 'commons-logging:commons-logging',
      version: '1.2'
      )
  }

  void 'jar without manifest and no version in filename gets resolved'() {
    // If no manifest info and no suitable file name - calculate sha1 hash
    knownJarCheck(
      jarName: 'groovy-no-manifest-info.jar',
      name: 'groovy-no-manifest-info.jar',
      version: '',
      hash: '1C1C8E5547A54F593B97584D45F3636F479B9498')
  }

  void 'try to determine lib name'() throws IOException {
    setup:
    File temp = File.createTempFile('temp', '.zip')

    expect:
    DependencyResolver.resolve(temp.toURI()).isEmpty()

    cleanup:
    temp.delete()
  }

  void 'try to determine non existing lib name'() throws IOException {
    setup:
    File temp = File.createTempFile('temp', '.zip')
    temp.delete()

    expect:
    DependencyResolver.resolve(temp.toURI()).isEmpty()
  }

  void 'try to determine invalid jar lib'() throws IOException {
    setup:
    File temp = File.createTempFile('temp', '.jar')
    temp.write("just a text file")

    expect:
    DependencyResolver.resolve(temp.toURI()).isEmpty()
  }

  void 'spring boot dependency'() throws IOException {
    when:
    URI zipPath = zipPath('datadog/telemetry/dependencies/spring-boot-app.jar')
    URI uri = new URI("jar:$zipPath!/BOOT-INF/lib/opentracing-util-0.33.0.jar!/")

    Dependency dep = DependencyResolver.resolve(uri).get(0)

    then:
    dep != null
    dep.name == 'io.opentracing:opentracing-util'
    dep.version == '0.33.0'
    dep.hash == null
    dep.source == 'opentracing-util-0.33.0.jar'
  }

  void 'spring boot dependency without trailing slash'() throws IOException {
    when:
    URI zipPath = zipPath('datadog/telemetry/dependencies/spring-boot-app.jar')
    URI uri = new URI("jar:$zipPath!/BOOT-INF/lib/opentracing-util-0.33.0.jar!")

    Dependency dep = DependencyResolver.resolve(uri).get(0)

    then:
    dep != null
    dep.name == 'io.opentracing:opentracing-util'
    dep.version == '0.33.0'
    dep.hash == null
    dep.source == 'opentracing-util-0.33.0.jar'
  }

  void 'spring boot dependency new style'() throws IOException {
    when:
    String zipPath = zipPath('datadog/telemetry/dependencies/spring-boot-app.jar').toString().replace("file:", "nested:")
    URI uri = new URI("jar:$zipPath/!BOOT-INF/lib/opentracing-util-0.33.0.jar!/")

    Dependency dep = DependencyResolver.resolve(uri).get(0)

    then:
    dep != null
    dep.name == 'io.opentracing:opentracing-util'
    dep.version == '0.33.0'
    dep.hash == null
    dep.source == 'opentracing-util-0.33.0.jar'
  }

  void 'spring boot dependency new style empty path'() throws IOException {
    when:
    URI uri = new URI("jar:nested:")
    List<Dependency> deps = DependencyResolver.resolve(uri)

    then:
    deps.isEmpty()
  }

  void 'spring boot dependency old style empty path'() throws IOException {
    when:
    URI uri = new URI("jar:file:")
    List<Dependency> deps = DependencyResolver.resolve(uri)

    then:
    deps.isEmpty()
  }

  void 'jar unknown'() throws IOException {
    when:
    URI uri = new URI("jar:unknown")
    List<Dependency> deps = DependencyResolver.resolve(uri)

    then:
    deps.isEmpty()
  }

  void 'spring boot dependency without maven metadata'() throws IOException {
    given:
    def innerJarData = new ByteArrayOutputStream()
    ZipOutputStream out = new ZipOutputStream(innerJarData)
    ZipEntry e = new ZipEntry("META-INF/MANIFEST.MF")
    out.putNextEntry(e)
    out.closeEntry()
    out.close()

    File file = new File(testDir, "app.jar")
    out = new ZipOutputStream(new FileOutputStream(file))
    e = new ZipEntry("BOOT-INF/lib/lib-1.0.jar")
    out.putNextEntry(e)
    out.write(innerJarData.toByteArray())
    out.closeEntry()
    out.close()

    when:
    URI uri = new URI("jar:file:" + file.getAbsolutePath() + "!/BOOT-INF/lib/lib-1.0.jar!/")
    List<Dependency> deps = DependencyResolver.resolve(uri)

    then:
    deps.size() == 1
    deps[0].source == "lib-1.0.jar"
    deps[0].hash != null
  }

  void 'fat jar with multiple pom.properties'() throws IOException {
    when:
    URI uri = zipPath('datadog/telemetry/dependencies/budgetapp.jar')

    List<Dependency> deps = DependencyResolver.resolve(uri)

    then:
    deps.size() == 105
    deps.every { it.hash == null }
  }

  void 'fat jar with two pom.properties'() throws IOException {
    when:
    URI uri = zipPath('datadog/telemetry/dependencies/budgetappreduced.jar')

    List<Dependency> deps = DependencyResolver.resolve(uri)
    Dependency dep1 = deps.get(0)
    Dependency dep2 = deps.get(1)

    then:
    deps.size() == 2
    dep1.name == 'org.yaml:snakeyaml' || dep2.name == 'org.yaml:snakeyaml'
    deps.every { it.hash == null }
  }

  void 'fat jar with two pom.properties one of them bad'() throws IOException {
    when:
    URI uri = zipPath('datadog/telemetry/dependencies/budgetappreducedbadproperties.jar')

    List<Dependency> deps = DependencyResolver.resolve(uri)
    Dependency dep1 = deps.get(0)

    then:
    deps.size() == 1
    dep1.name == 'org.yaml:snakeyaml'
    deps.every { it.hash == null }
  }

  void 'invalid pom.properties results in fallback'() {
    // this jar has an invalid pom.properties and it should be resolved with its file name
    expect:
    knownJarCheck(
      jarName: 'invalidpomproperties.jar',
      name: 'invalidpomproperties',
      version: '',
      hash: '6438819DAB9C9AC18D8A6922C8A923C2ADAEA85D')
  }

  void 'attempt to extract dependencies from directory'() {
    given:
    File dir = new File(testDir, 'dir')
    dir.mkdirs()

    when:
    def deps = DependencyResolver.resolve(dir.toURI())

    then:
    deps.isEmpty()

    when: 'resolve without catching exceptions'
    deps = DependencyResolver.internalResolve(dir.toURI())

    then: 'it does not throw'
    deps.isEmpty()
  }

  void 'attempt to extract dependencies from directory within jar'() {
    given:
    File file = new File(testDir, "app.jar")
    ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file))
    ZipEntry e = new ZipEntry("classes/")
    out.putNextEntry(e)
    out.closeEntry()
    out.close()

    when:
    def deps = DependencyResolver.resolve(new URI('jar:file:' + file.getAbsolutePath() + "!/classes!/"))

    then:
    deps.isEmpty()

    when: 'resolve without catching exceptions'
    deps = DependencyResolver.internalResolve(new URI('jar:file:' + file.getAbsolutePath() + "!/classes!/"))

    then: 'it does not throw'
    deps.isEmpty()
  }

  private static void knownJarCheck(Map opts) {
    File jarFile = getJar(opts['jarName'])
    List<Dependency> deps = DependencyResolver.resolve(jarFile.toURI())

    assert deps.size() == 1
    Dependency dep = deps.get(0)
    assert dep != null
    assert dep.source == opts['jarName']
    assert dep.name == opts['name']
    assert dep.version == opts['version']
    assert dep.hash == opts['hash']
  }

  void 'JBoss may use the "jar:file" format to reference jar files instead of nested jars'(){
    setup:
    final attributes = [
      'Bundle-SymbolicName'   : null,
      'Bundle-Name'           : null,
      'Bundle-Version'        : null,
      'Implementation-Title'  : 'JUnit',
      'Implementation-Version': '4.12',
    ]
    final file = prepareJar("junit-4.12.jar", attributes)
    final uri = new URI("jar:"+file.toURI()+"!/")

    when:
    final deps = DependencyResolver.resolve(uri)

    then:
    deps.size() == 1
  }

  private static URI zipPath(String outerJar) {
    URL outerJarUrl = Thread.currentThread()
      .contextClassLoader
      .getResource(outerJar)

    assert outerJarUrl != null : "Resource not found: ${outerJar}"

    outerJarUrl.toURI()
  }
}
