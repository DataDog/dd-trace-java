package datadog.trace.core

import datadog.trace.test.util.DDSpecification
import org.apache.commons.codec.binary.Base64

import java.nio.ByteBuffer

class Base64EncoderTest extends DDSpecification {
  def "encode empty array"() {
    setup:
    def arr = new byte[0]

    when:
    def encoded = Base64Encoder.INSTANCE.encode(arr)

    then:
    arr == Base64.decodeBase64(encoded)
  }

  def "encode array exact"() {
    setup:
    def arr = [10, 50, 100, 130, 200, 255] as byte[]

    when:
    def encoded = Base64Encoder.INSTANCE.encode(arr)

    then:
    arr == Base64.decodeBase64(encoded)
  }

  def "encode array with remainder"() {
    setup:
    def arr = [10, 50, 100, 130, 200, 255, 16] as byte[]

    when:
    def encoded = Base64Encoder.INSTANCE.encode(arr)

    then:
    arr == Base64.decodeBase64(encoded)
  }

  def "encode wrapped byte buffer exact"() {
    setup:
    def arr = [10, 50, 100, 130, 200, 255] as byte[]
    def buf = ByteBuffer.wrap(arr)

    when:
    def encoded = Base64Encoder.INSTANCE.encode(buf)

    then:
    arr == Base64.decodeBase64(encoded.array())
  }

  def "encode wrapped byte buffer with remainder"() {
    setup:
    def arr = [10, 50, 100, 130, 200, 255, 16] as byte[]
    def buf = ByteBuffer.wrap(arr)

    when:
    def encoded = Base64Encoder.INSTANCE.encode(buf)

    then:
    arr == Base64.decodeBase64(encoded.array())
  }
}
