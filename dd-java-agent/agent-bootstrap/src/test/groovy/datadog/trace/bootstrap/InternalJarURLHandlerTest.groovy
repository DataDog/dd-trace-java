package datadog.trace.bootstrap

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class InternalJarURLHandlerTest extends DDSpecification {

  @Shared
  URL testJarLocation = new File("src/test/resources/classloader-test-jar/testjar-jdk8").toURI().toURL()

  @Shared
  DatadogClassLoader.JarIndex index = new DatadogClassLoader.JarIndex(testJarLocation)


  def "test extract packages"() {
    setup:
    InternalJarURLHandler handler = new InternalJarURLHandler(dir, index.getPackages(dir), index.jarFile)
    expect:
    packages == handler.getPackages()

    where:
    dir      | packages
    "parent" | ['a', 'a.b', 'a.b.c'].toSet()
    "child"  | ['x', 'x.y', 'x.y.z'].toSet()
  }

  def "test get URL"() {
    setup:
    InternalJarURLHandler handler = new InternalJarURLHandler(dir, index.getPackages(dir), index.jarFile)
    when:
    URLConnection connection = handler.openConnection(new File(file).toURI().toURL())
    assert connection != null
    byte[] data = new byte[128]
    int read = connection.getInputStream().read(data)
    then:
    read > 0

    where:
    dir      | file
    "parent" | '/a/A.class'
    "parent" | '/a/b/B.class'
    "parent" | '/a/b/c/C.class'
    "child"  | '/x/X.class'
    "child"  | '/x/y/Y.class'
    "child"  | '/x/y/z/Z.class'
  }


  def "test read class twice"() {
    // guards against caching the stream
    setup:
    InternalJarURLHandler handler = new InternalJarURLHandler(dir, index.getPackages(dir), index.jarFile)
    when:
    URLConnection connection = handler.openConnection(new File(file).toURI().toURL())
    assert connection != null
    InputStream is = connection.getInputStream()
    is.close()
    connection = handler.openConnection(new File(file).toURI().toURL())
    assert connection != null
    is = connection.getInputStream()
    byte[] data = new byte[128]
    int read = is.read(data)

    then:
    read > 0

    where:
    dir      | file
    "parent" | '/a/A.class'
    "parent" | '/a/b/B.class'
    "parent" | '/a/b/c/C.class'
    "child"  | '/x/X.class'
    "child"  | '/x/y/Y.class'
    "child"  | '/x/y/z/Z.class'
  }

  def "handle not found"() {
    setup:
    InternalJarURLHandler handler = new InternalJarURLHandler(dir, index.getPackages(dir), index.jarFile)
    when:
    handler.openConnection(new File(file).toURI().toURL())
    then:
    // not going to specify (and thereby constrain) the sub type because it doesn't matter
    thrown IOException

    // permuted
    where:
    dir      | file
    "child"  | '/a/A.class'
    "child"  | '/a/b/B.class'
    "child"  | '/a/b/c/C.class'
    "parent" | '/x/X.class'
    "parent" | '/x/y/Y.class'
    "parent" | '/x/y/z/Z.class'
  }
}
