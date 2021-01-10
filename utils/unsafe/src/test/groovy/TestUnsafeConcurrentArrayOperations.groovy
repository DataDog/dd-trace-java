import datadog.trace.core.unsafe.UnsafeConcurrentArrayOperations
import datadog.trace.test.util.DDSpecification

class TestUnsafeConcurrentArrayOperations extends DDSpecification {

  def "test unsafe concurrent array operations"() {
    setup:
    Object[] objects = new Object[2]
    boolean[] booleans = new boolean[2]
    int[] ints = new int[2]
    long[] longs = new long[2]
    def arrayOperations = new UnsafeConcurrentArrayOperations()

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
