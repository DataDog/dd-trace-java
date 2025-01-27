package datadog.trace.civisibility.ipc.serialization

import spock.lang.Specification

import java.nio.ByteBuffer

class PolymorphicSerializerTest extends Specification {

  def "test polymorphic serialization"() {
    given:
    PolymorphicSerializer<Parent> serializer = new PolymorphicSerializer<>(ChildA, ChildB)

    when:
    def s = new Serializer()
    serializer.serialize(original, s)
    def buffer = s.flush()
    def copy = serializer.deserialize(buffer)

    then:
    copy == original

    where:
    original << [null, new ChildA(123), new ChildB("test")]
  }

  private static interface Parent extends SerializableType {}

  private static class ChildA implements Parent {
    private final int intField

    ChildA(int intField) {
      this.intField = intField
    }

    @Override
    void serialize(Serializer s) {
      s.write(intField)
    }

    static ChildA deserialize(ByteBuffer buffer) {
      return new ChildA(Serializer.readInt(buffer))
    }

    boolean equals(o) {
      return o != null && getClass() == o.class && intField == ((ChildA) o).intField
    }

    int hashCode() {
      return intField
    }
  }

  private static class ChildB implements Parent {
    private final String stringField

    ChildB(String stringField) {
      this.stringField = stringField
    }

    @Override
    void serialize(Serializer s) {
      s.write(stringField)
    }

    static ChildB deserialize(ByteBuffer buffer) {
      return new ChildB(Serializer.readString(buffer))
    }

    boolean equals(o) {
      return o != null && getClass() == o.class && stringField == ((ChildB) o).stringField
    }

    int hashCode() {
      return stringField.hashCode()
    }
  }
}
