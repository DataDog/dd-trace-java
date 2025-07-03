package datadog.trace.civisibility.ipc

import datadog.environment.JavaVirtualMachine
import spock.lang.IgnoreIf
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.util.concurrent.ThreadLocalRandom

@IgnoreIf(reason = "JVM crash with OpenJ9", value = {
  JavaVirtualMachine.isJ9()
})
class ChannelContextTest extends Specification {

  def "test message is read"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

    when:
    def channel = new InMemoryReadableByteChannel()
    channel.writeInput((byte) (length >> 24)) // first byte of the message length

    context.read(channel)

    channel.writeInput((byte) (length >> 16)) // second byte of the message length
    channel.writeInput((byte) (length >> 8)) // third byte of the message length
    channel.writeInput((byte) length) // fourth byte of the message length

    byte[] message = new byte[length]
    Arrays.fill(message, (byte) 42)
    channel.writeInput(message)

    context.read(channel)

    then:
    messages.size() == 1
    Arrays.equals(messages.poll(), message)

    where:
    length << [1, 2, 4, 7, 8, 12, 13, 16, 21, 24, 100, 255, 256, 260, 300, 1000]
  }

  def "test partial second message length write"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

    when:
    def channel = new InMemoryReadableByteChannel()
    // message length (4 bytes) + message (3 bytes) leave
    // 3 bytes in the buffer for the next message length,
    // which is not enough
    writeMessage("123".bytes, channel)
    writeMessage("67890".bytes, channel)

    context.read(channel)

    then:
    messages.size() == 2
    Arrays.equals(messages.poll(), "123".bytes)
    Arrays.equals(messages.poll(), "67890".bytes)
  }

  def "test message with length larger than max short value"() {
    given:
    def messages = new LinkedList<byte[]>()
    def context = new ChannelContext(10, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    def context = new ChannelContext(bufferCapacity, { ByteBuffer bb ->
      messages.offer(bb.array())
      return new ByteBuffer[0]
    })

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
    bufferCapacity << [4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16]
  }

  def "test response is written: buffer #channelOutputBufferCapacity response length #responseLength"() {
    given:
    def expectedResponse = new byte[responseLength]
    ThreadLocalRandom.current().nextBytes(expectedResponse)

    def context = new ChannelContext(10, {
      return new ByteBuffer[] {
        ByteBuffer.allocate(Integer.BYTES).putInt(expectedResponse.length).flip(),
        ByteBuffer.wrap(expectedResponse)
      }
    })

    when:
    def channel = new InMemoryReadableByteChannel(channelOutputBufferCapacity)
    writeMessage("test".bytes, channel)

    context.read(channel)
    while (context.hasPendingResponses()) {
      context.write(channel)
    }

    def response = readMessage(channel)

    then:
    response == expectedResponse

    where:
    channelOutputBufferCapacity | responseLength
    2 | 4
    3 | 4
    7 | 8
    8 | 4
    8 | 7
    8 | 8
    8 | 16
    256 | 256
    256 | 1024
    256 | 1024 * 1024
  }

  private void writeMessage(byte[] bytes, InMemoryReadableByteChannel channel) {
    ByteBuffer header = ByteBuffer.allocate(Integer.BYTES)
    header.putInt(bytes.length)
    header.flip()
    channel.writeInput(header.array())
    channel.writeInput(bytes)
  }

  private byte[] readMessage(InMemoryReadableByteChannel channel) {
    def output = channel.readOutput()
    def payload = Arrays.copyOfRange(output, Integer.BYTES, output.length)
    return payload
  }

  private static final class InMemoryReadableByteChannel implements ByteChannel {
    private boolean closed
    private boolean eos
    private final Deque<byte[]> input = new ArrayDeque<>()
    private final Deque<byte[]> output = new ArrayDeque<>()

    private final int outputBufferCapacity

    InMemoryReadableByteChannel() {
      this(Integer.MAX_VALUE)
    }

    InMemoryReadableByteChannel(int outputBufferCapacity) {
      this.outputBufferCapacity = outputBufferCapacity
    }

    void writeInput(byte... bytes) {
      input.offer(bytes)
    }

    byte[] readOutput() {
      int cumulativeSize = 0
      for (byte[] b : (this.output)) {
        cumulativeSize += b.length
      }

      int idx = 0
      byte[] bytes = new byte[cumulativeSize]
      while (!this.output.empty) {
        byte[] b = this.output.poll()
        System.arraycopy(b, 0, bytes, idx, b.length)
        idx += b.length
      }
      return bytes
    }

    void eos() {
      eos = true
    }

    @Override
    int read(ByteBuffer dst) throws IOException {
      if (closed) {
        throw new IOException("Channel is closed")
      }
      if (eos && input.isEmpty()) {
        return -1
      }

      int read = 0
      while (!input.isEmpty() && dst.remaining() > 0) {
        byte[] b = input.poll()
        if (dst.remaining() >= b.length) {
          dst.put(b)
          read += b.length
        } else {
          byte[] whatFits = new byte[dst.remaining()]
          byte[] remainder = new byte[b.length - dst.remaining()]
          System.arraycopy(b, 0, whatFits, 0, whatFits.length)
          System.arraycopy(b, whatFits.length, remainder, 0, remainder.length)

          dst.put(whatFits)
          input.offerFirst(remainder)
          read += whatFits.length
        }
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
      int length = Math.min(outputBufferCapacity, src.remaining())
      byte[] bytes = new byte[length]
      src.get(bytes, 0, length)
      this.output.offer(bytes)
      return length
    }
  }
}
