package datadog.remoteconfig

import spock.lang.Specification

class SizeCheckedInputStreamSpecification extends Specification {

  ByteArrayInputStream stream = Mock()

  def 'sized checked stream doesn\'t read more than it needs'(){
    setup:
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 500)

    when:
    byte[] buffer = new byte[1000]
    sizedStream.read(buffer)

    then:
    1 * stream.read(_,0,500) >> 500

    when:
    sizedStream.read(buffer)

    then:
    def e = thrown(IOException)
    e.message == "Reached maximum bytes for this stream: 500"
  }

  def 'sized checked stream read min(buffer size,left-capacity)'(){
    setup:
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 499)

    when:
    byte[] buffer = new byte[100]
    sizedStream.read(buffer)
    sizedStream.read(buffer)
    sizedStream.read(buffer)
    sizedStream.read(buffer)
    sizedStream.read(buffer)

    then:
    4 * stream.read(_,0,100) >> 100
    1 * stream.read(_,0,99) >> 99

    when:
    sizedStream.read(buffer)

    then:
    def e = thrown(IOException)
    e.message == "Reached maximum bytes for this stream: 499"
  }

  def 'sized checked stream respect end of stream'(){
    setup:
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 100)

    when:
    byte[] buffer = new byte[100]
    sizedStream.read(buffer)
    sizedStream.read(buffer)
    sizedStream.read(buffer)

    then:
    1 * stream.read(_,0,100) >> 70
    1 * stream.read(_,0,30) >> -1
    1 * stream.read(_,0,30) >> -1
  }

  def 'sized checked stream created byte by byte'(){
    setup:
    SizeCheckedInputStream sizedStream = new SizeCheckedInputStream(stream, 200)

    when:
    while(sizedStream.read() != -1) {}

    then:
    100 * stream.read() >> 1
    1 * stream.read() >> -1

    when:
    while(sizedStream.read() != -1) {}

    then:
    100 * stream.read() >> 1
    def e = thrown(IOException)
    e.message == "Reached maximum bytes for this stream: 200"
  }
}
