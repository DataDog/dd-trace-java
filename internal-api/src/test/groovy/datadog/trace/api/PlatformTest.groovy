package datadog.trace.api

import datadog.trace.test.util.DDSpecification
import org.junit.Assume

class PlatformTest extends DDSpecification {

  def "is at least java 7"() {
    expect: Platform.isJavaVersionAtLeast(7)
  }

  def "is at least java 8 when run on JDK1.8 or later"() {
    Assume.assumeTrue(!System.getProperty("java.version").startsWith("1.")
      || System.getProperty("java.version").startsWith("1.8."))
    expect: Platform.isJavaVersionAtLeast(8) && Platform.isJavaVersionAtLeast(7)
  }

  def "is at least java 11 when run on JDK11 or later"() {
    Assume.assumeTrue(!System.getProperty("java.version").startsWith("1.")
    && !(System.getProperty("java.version").startsWith("9.")
      || System.getProperty("java.version").startsWith("10.")))
    expect: Platform.isJavaVersionAtLeast(11) && Platform.isJavaVersionAtLeast(8)
  }

  def "test parse #version yields #expected"() {
    when:
    int parsedVersion = Platform.parseJavaVersion(version)
    then:
    expected == parsedVersion

    where:
    version     | expected
    "1.7"       | 7
    "1.7.0"     | 7
    "1.8"       | 8
    "1.8.0"     | 8
    "1.8.0_212" | 8
    "9-ea"      | 9
    "9.1.2"     | 9
    "11"        | 11
    "11.0.6"    | 11
    "14"        | 14
    "15"        | 15
  }


  def "test default concurrent array operations"() {
    setup:
    Object[] objects = new Object[2]
    boolean[] booleans = new boolean[2]
    int[] ints = new int[2]
    long[] longs = new long[2]
    when: "in the unlikely case that this class is loaded"
    def arrayOperations = Platform.concurrentArrayOperations()
    then: "it won't NPE"
    null != arrayOperations
    when: "it is used to set array values"
    arrayOperations.putObjectVolatile(objects, 0, "foo")
    arrayOperations.putOrderedObject(objects, 1, "bar")
    arrayOperations.putBooleanVolatile(booleans, 0, true)
    arrayOperations.putIntVolatile(ints, 0, 1)
    arrayOperations.putIntVolatile(ints, 0, 1)
    arrayOperations.putOrderedInt(ints, 1, 2)
    arrayOperations.putLongVolatile(longs, 0, 1)
    arrayOperations.putOrderedLong(longs, 1, 2)
    then: "it behaves like normal array accesses"
    arrayOperations.getObjectVolatile(objects, 0) == "foo"
    arrayOperations.getObjectVolatile(objects, 1) == "bar"
    arrayOperations.getBooleanVolatile(booleans, 0)
    arrayOperations.getIntVolatile(ints, 0) == 1
    arrayOperations.getIntVolatile(ints, 1) == 2
    arrayOperations.getLongVolatile(longs, 0) == 1
    arrayOperations.getLongVolatile(longs, 1) == 2
  }
}
