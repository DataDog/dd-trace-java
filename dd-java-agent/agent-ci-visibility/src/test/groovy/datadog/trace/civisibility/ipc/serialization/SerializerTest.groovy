package datadog.trace.civisibility.ipc.serialization


import spock.lang.Specification

import java.nio.ByteBuffer

import static datadog.trace.civisibility.TestUtils.bitset

class SerializerTest extends Specification {

  def "test int serialization: #i"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write(i)
    def buf = serializer.flush()

    then:
    Serializer.readInt(buf) == i

    where:
    i << [-1, 0, 1, 2, 3, 4, 8, 16, 255, 500, 1000, 2000, 16000, 33000, 1000000, 2000000000]
  }

  def "test long serialization: #l"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((long) l)
    def buf = serializer.flush()

    then:
    Serializer.readLong(buf) == l

    where:
    l << [
      -1,
      0,
      1,
      2,
      3,
      255,
      500,
      1000,
      0xABL,
      0xABCDL,
      0xABCD1234L,
      0xAB12CD34EF56A18BL,
      0xFFFFFFFFFFFFFFFFL
    ]
  }

  def "test string serialization: #s"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((String) s)
    def buf = serializer.flush()

    then:
    Serializer.readString(buf) == s

    where:
    s << [
      null,
      "",
      "a",
      "abc",
      "abcdefghabcdefghabcdefghabcdefghabcdefghabcdefghabcdefghabcdefghabcdefghabcdefghabcdefghabcdefghabcdefgh"
    ]
  }

  def "test collection of string serialization: #c"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((Collection) c)
    def buf = serializer.flush()

    then:
    Serializer.readStringList(buf) == c

    where:
    c << [null, [], ["a"], ["a", "b", "c"], ["a", null, null, null]]
  }

  def "test collection of POJO serialization: #c"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((Collection) c, MyPojo.&serialize)
    def buf = serializer.flush()

    then:
    Serializer.readList(buf, MyPojo.&deserialize) == c

    where:
    c << [null, [], [new MyPojo(123, "abc")], [new MyPojo(123, "abc"), new MyPojo(456, "test")]]
  }

  def "test map of string serialization: #m"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((Map) m)
    def buf = serializer.flush()

    then:
    Serializer.readStringMap(buf) == m

    where:
    m << [null, [:], ["a": "b"], ["a": "b", "1": "2"], [null: "b", "1": null]]
  }

  def "test map deserialization with provider: #m #clazz #provider"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((Map) m, keySerializer, Serializer::write)
    def buf = serializer.flush()

    then:
    def deserializedMap = Serializer.readMap(buf, provider, keyDeserializer, Serializer::readString)
    deserializedMap == m
    deserializedMap.getClass() == clazz

    where:
    m                                  | clazz         | provider                            | keySerializer     | keyDeserializer
    [:]                                | HashMap       | HashMap::new                        | Serializer::write | Serializer::readString
    ["a": "1", "b": "2"]               | HashMap       | HashMap::new                        | Serializer::write | Serializer::readString
    [:]                                | LinkedHashMap | LinkedHashMap::new                  | Serializer::write | Serializer::readString
    ["a": "1", "b": "2"]               | LinkedHashMap | LinkedHashMap::new                  | Serializer::write | Serializer::readString
    [:]                                | TreeMap       | TreeMap::new                        | Serializer::write | Serializer::readString
    ["a": "1", "b": "2"]               | TreeMap       | TreeMap::new                        | Serializer::write | Serializer::readString
    [:]                                | EnumMap       | (() -> new EnumMap<>(MyEnum.class)) | MyEnum::serialize | MyEnum::deserialize
    [(MyEnum.A): "1", (MyEnum.B): "2"] | EnumMap       | (() -> new EnumMap<>(MyEnum.class)) | MyEnum::serialize | MyEnum::deserialize
  }

  def "test mixed serialization"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((byte) 3)
    serializer.write(123)
    serializer.write("test")
    serializer.write(456)
    serializer.write(["a", "b", "c"])
    serializer.write([new MyPojo(1, "a"), new MyPojo(2, "b")], MyPojo.&serialize)
    serializer.write("test2")
    serializer.write(["a": "b", "1": "2"])

    def buf = ByteBuffer.allocate(serializer.length())
    serializer.flush(buf)
    buf.flip()

    then:
    Serializer.readByte(buf) == 3
    Serializer.readInt(buf) == 123
    Serializer.readString(buf) == "test"
    Serializer.readInt(buf) == 456
    Serializer.readStringList(buf) == ["a", "b", "c"]
    Serializer.readList(buf, MyPojo.&deserialize) == [new MyPojo(1, "a"), new MyPojo(2, "b")]
    Serializer.readString(buf) == "test2"
    Serializer.readStringMap(buf) == ["a": "b", "1": "2"]
  }

  def "test bitset serialization: #bitset"() {
    given:
    def serializer = new Serializer()

    when:
    serializer.write((BitSet) bitset)
    def buf = serializer.flush()

    then:
    Serializer.readBitSet(buf) == bitset

    where:
    bitset << [null, bitset(), bitset(1), bitset(999), bitset(1, 2, 3), bitset(1, 2, 3, 18, 157, 956, 1234567)]
  }

  private static final class MyPojo {
    private final int a
    private final String b

    private MyPojo(int a, String b) {
      this.a = a
      this.b = b
    }

    @Override
    String toString() {
      "$a / $b"
    }

    boolean equals(o) {
      if (this.is(o)) {
        return true
      }
      if (o == null || getClass() != o.class) {
        return false
      }

      MyPojo myPojo = (MyPojo) o
      return a == myPojo.a && b == myPojo.b
    }

    int hashCode() {
      int result
      result = a
      result = 31 * result + (b != null ? b.hashCode() : 0)
      return result
    }

    private static void serialize(Serializer serializer, MyPojo pojo) {
      serializer.write(pojo.a)
      serializer.write(pojo.b)
    }

    private static MyPojo deserialize(ByteBuffer buf) {
      return new MyPojo(Serializer.readInt(buf), Serializer.readString(buf))
    }
  }

  private enum MyEnum {
    A(1),
    B(2),
    C(3)

    public final int val

    MyEnum(int val) {
      this.val = val
    }

    private static void serialize(Serializer serializer, MyEnum en) {
      serializer.write(en.val)
    }

    private static MyEnum deserialize(ByteBuffer buf) {
      int val = Serializer.readInt(buf)
      for (MyEnum en : values()) {
        if (en.val == val) {
          return en
        }
      }
      return null
    }
  }
}
