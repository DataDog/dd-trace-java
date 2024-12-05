package datadog.telemetry.dependency

class JarReaderSpecification extends DepSpecification {

  void 'read plain jar with manifest and no pom.properties'() {
    given:
    String jarPath = getJar("bson-4.2.0.jar").getAbsolutePath()

    when:
    def result = JarReader.readJarFile(jarPath)

    then:
    result.jarName == "bson-4.2.0.jar"
    result.pomProperties.isEmpty()
    result.manifest != null
    result.manifest.getValue("Bundle-Name") == "bson"
  }

  void 'read jar without manifest'() {
    given:
    String jarPath = getJar("groovy-no-manifest-info.jar").getAbsolutePath()

    when:
    def result = JarReader.readJarFile(jarPath)

    then:
    result.jarName == "groovy-no-manifest-info.jar"
    result.pomProperties.isEmpty()
    result.manifest != null
    result.manifest.isEmpty()
  }

  void 'read plain jar with manifest and pom.properties'() {
    given:
    String jarPath = getJar("commons-logging-1.2.jar").getAbsolutePath()

    when:
    def result = JarReader.readJarFile(jarPath)

    then:
    result.jarName == "commons-logging-1.2.jar"
    result.pomProperties.size() == 1
    def properties = result.pomProperties['META-INF/maven/commons-logging/commons-logging/pom.properties']
    properties != null
    properties.groupId == "commons-logging"
    properties.artifactId == "commons-logging"
    properties.version == "1.2"
    result.manifest != null
    result.manifest.getValue("Bundle-Name") == "Apache Commons Logging"
  }

  void 'read nested jar'() {
    given:
    String outerPath = getJar("spring-boot-app.jar").getAbsolutePath()

    when:
    def result = JarReader.readNestedJarFile(outerPath, "BOOT-INF/lib/opentracing-util-0.33.0.jar")

    then:
    result.jarName == "opentracing-util-0.33.0.jar"
    result.pomProperties.size() == 1
    def properties = result.pomProperties['META-INF/maven/io.opentracing/opentracing-util/pom.properties']
    properties.groupId == "io.opentracing"
    properties.artifactId == "opentracing-util"
    properties.version == "0.33.0"
    result.manifest != null
    result.manifest.getValue("Automatic-Module-Name") == "io.opentracing.util"
  }

  void 'non-existent simple jar'() {
    given:
    String jarPath = "non-existent.jar"

    when:
    JarReader.readJarFile(jarPath)

    then:
    thrown(IOException)
  }

  void 'non-existent outer jar for nested jar'() {
    when:
    JarReader.readNestedJarFile("non-existent.jar", "BOOT-INF/lib/opentracing-util-0.33.0.jar")

    then:
    thrown(IOException)
  }

  void 'non-existent inner jar for nested jar'() {
    given:
    String outerPath = getJar("spring-boot-app.jar").getAbsolutePath()

    when:
    JarReader.readNestedJarFile(outerPath, "BOOT-INF/lib/non-existent.jar")

    then:
    thrown(IOException)
  }

  void 'doubly nested jar path'() {
    when:
    JarReader.readNestedJarFile("non-existent.jar", "BOOT-INF/lib/opentracing-util-0.33.0.jar/third")

    then:
    thrown(IOException)
  }

  void 'empty nested jar path'() {
    when:
    JarReader.readNestedJarFile("non-existent.jar", "")

    then:
    thrown(IOException)
  }
}
