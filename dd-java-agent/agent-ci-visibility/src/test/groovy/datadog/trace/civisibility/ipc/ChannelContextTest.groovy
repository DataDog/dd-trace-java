package datadog.trace.civisibility.ipc

import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.nio.channels.ReadableByteChannel
import java.util.concurrent.ThreadLocalRandom

class ChannelContextTest extends Specification {

  def "test message is read"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    writeMessage("test".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 1
    Arrays.equals(messages.poll(), "test".bytes)
  }

  def "test message equal to buffer size is read"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    writeMessage("123456".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 1
    Arrays.equals(messages.poll(), "123456".bytes)
  }

  def "test message larger than buffer size is read"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    writeMessage("this is a very long message that exceeds the size of the buffer".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 1
    Arrays.equals(messages.poll(), "this is a very long message that exceeds the size of the buffer".bytes)
  }

  def "test multiple messages are read"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    writeMessage("t".bytes, channel)
    writeMessage("e".bytes, channel)
    writeMessage("s".bytes, channel)
    writeMessage("t".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 4
    Arrays.equals(messages.poll(), "t".bytes)
    Arrays.equals(messages.poll(), "e".bytes)
    Arrays.equals(messages.poll(), "s".bytes)
    Arrays.equals(messages.poll(), "t".bytes)
  }

  def "test multiple messages larger than buffer size is read"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    writeMessage("this is a very long message that exceeds the size of the buffer".bytes, channel)
    writeMessage("this is another very long message that exceeds the size of the buffer".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 2
    Arrays.equals(messages.poll(), "this is a very long message that exceeds the size of the buffer".bytes)
    Arrays.equals(messages.poll(), "this is another very long message that exceeds the size of the buffer".bytes)
  }

  def "test multiple messages are read in two attempts"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    writeMessage("t".bytes, channel)
    writeMessage("e".bytes, channel)

    context.read(channel)

    writeMessage("s".bytes, channel)
    writeMessage("t".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 4
    Arrays.equals(messages.poll(), "t".bytes)
    Arrays.equals(messages.poll(), "e".bytes)
    Arrays.equals(messages.poll(), "s".bytes)
    Arrays.equals(messages.poll(), "t".bytes)
  }

  def "test partial message length write: #length"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    channel.write((byte) (length >> 8)) // first byte of the message length

    context.read(channel)

    channel.write((byte) length) // second byte of the message length

    byte[] message = new byte[length]
    Arrays.fill(message, (byte) 42)
    channel.write(message)

    context.read(channel)

    then:
    messages.size() == 1
    Arrays.equals(messages.poll(), message)

    where:
    length << [ 1, 2, 4, 7, 8, 12, 13, 16, 21, 24, 100, 255, 256, 260, 300, 1000 ]
  }

  def "test partial second message length write"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(8, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    // message length (2 bytes) + message (5 bytes) leave
    // only 1 byte in the buffer for the next message length,
    // which is not enough
    writeMessage("12345".bytes, channel)
    writeMessage("67890".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 2
    Arrays.equals(messages.poll(), "12345".bytes)
    Arrays.equals(messages.poll(), "67890".bytes)
  }

  def "test message with length larger than max short value"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(1024, messages::offer)

    def message = new byte[Short.MAX_VALUE + 1]
    ThreadLocalRandom.current().nextBytes(message)

    when:
    def channel = new InMemoryReadableByteChannel()
    writeMessage(message, channel)

    context.read(channel)

    then:
    messages.size() == 1
    Arrays.equals(messages.poll(), message)
  }

  def "randomized test: buffer capacity #bufferCapacity"() {
    given:
    byte[][] generatedMessages = new byte[1000]
    for (int i = 0; i < generatedMessages.length; i++) {
      generatedMessages[i] = new byte[ThreadLocalRandom.current().nextInt(1, 1000)]
      ThreadLocalRandom.current().nextBytes(generatedMessages[i])
    }

    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(bufferCapacity, messages::offer)

    when:
    def channel = new InMemoryReadableByteChannel()
    for (int i = 0; i < generatedMessages.length; i++) {
      writeMessage(generatedMessages[i], channel)
      if (i % 10 == 0) {
        context.read(channel)
      }
    }
    context.read(channel)

    then:
    messages.size() == generatedMessages.length
    for (int i = 0; i < generatedMessages.length; i++) {
      Arrays.equals(messages.get(i), generatedMessages[i])
    }

    where:
    bufferCapacity << [ 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 ]
  }

  private void writeMessage(byte[] bytes, InMemoryReadableByteChannel channel) {
    channel.write((byte) (bytes.length >> 8), (byte) bytes.length)
    channel.write(bytes)
  }

  private static final class InMemoryReadableByteChannel implements ByteChannel {
    private boolean closed
    private boolean eos
    private final Queue<Byte> remaining = new ArrayDeque<>()

    void write(byte... bytes) {
      for (byte b : bytes) {
        remaining.offer(b)
      }
    }

    void eos() {
      eos = true
    }

    @Override
    int read(ByteBuffer dst) throws IOException {
      if (closed) {
        throw new IOException("Channel is closed")
      }
      if (eos && remaining.isEmpty()) {
        return -1
      }
      int read = 0
      while (!remaining.isEmpty() && dst.remaining() > 0) {
        dst.put(remaining.poll())
        read++
      }
      return read
    }

    @Override
    boolean isOpen() {
      !closed
    }

    @Override
    void close() {
      closed = true
    }

    @Override
    int write(ByteBuffer src) throws IOException {
      return src.remaining()
    }
  }
}
