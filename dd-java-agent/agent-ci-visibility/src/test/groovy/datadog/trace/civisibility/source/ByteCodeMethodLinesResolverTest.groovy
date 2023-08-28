package datadog.trace.civisibility.source


import org.spockframework.util.IoUtil
import spock.lang.Specification

class ByteCodeMethodLinesResolverTest extends Specification {

  def "test method lines resolution"() {
    setup:
    def aTestMethod = NestedClass.getDeclaredMethod("aTestMethod")

    when:
    def methodLinesResolver = new ByteCodeMethodLinesResolver()
    def methodLines = methodLinesResolver.getLines(aTestMethod)

    then:
    methodLines.isValid()
    methodLines.startLineNumber > 0
    methodLines.finishLineNumber > methodLines.startLineNumber
  }

  def "test invalid method lines resolution"() {
    setup:
    def aTestMethod = NestedClass.getDeclaredMethod("abstractMethod")

    when:
    def methodLinesResolver = new ByteCodeMethodLinesResolver()
    def methodLines = methodLinesResolver.getLines(aTestMethod)

    then:
    !methodLines.isValid()
  }

  def "test returns empty method lines when class cannot be loaded"() {
    setup:
    def misbehavingClassLoader = new MisbehavingClassLoader()

    Utils.getClassStream(NestedClass).withCloseable { stream ->
      def baos = new ByteArrayOutputStream()
      IoUtil.copyStream(stream, baos)
      misbehavingClassLoader.putClass(NestedClass.name, baos.toByteArray())
    }

    def misbehavingClass = misbehavingClassLoader.loadClass(NestedClass.name)
    def misbehavingMethod = misbehavingClass.getDeclaredMethod("aTestMethod")

    when:
    def methodLinesResolver = new ByteCodeMethodLinesResolver()
    def methodLines = methodLinesResolver.getLines(misbehavingMethod)

    then:
    !methodLines.isValid()
  }

  def "test returns empty method lines when unknown method is attempted to be resolved"() {
    setup:
    def aTestMethod = NestedClass.getDeclaredMethod("abstractMethod")
    def classMethodLines = new ByteCodeMethodLinesResolver.ClassMethodLines()

    when:
    def methodLines = classMethodLines.get(aTestMethod)

    then:
    !methodLines.isValid()
  }

  private static abstract class NestedClass {
    static double aTestMethod() {
      def random = Math.random()
      return random
    }

    abstract void abstractMethod()
  }

  private static final class MisbehavingClassLoader extends ClassLoader {

    private final Map<String, byte[]> classes = new HashMap<>()

    @Override
    InputStream getResourceAsStream(String name) {
      throw new RuntimeException("Something went wrong")
    }

    @Override
    Class<?> loadClass(String name) throws ClassNotFoundException {
      def bytes = classes.get(name)
      if (bytes != null) {
        return defineClass(name, bytes, 0, bytes.length)
      }
      return super.loadClass(name)
    }

    void putClass(String name, byte[] bytes) {
      classes.put(name, bytes)
    }
  }
}

