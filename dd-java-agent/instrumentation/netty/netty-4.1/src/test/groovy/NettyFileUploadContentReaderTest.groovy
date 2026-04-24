import datadog.trace.instrumentation.netty41.NettyFileUploadContentReader
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.multipart.FileUpload
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.charset.StandardCharsets
import java.nio.file.Path

class NettyFileUploadContentReaderTest extends Specification {

  @TempDir
  Path tempDir

  // --- in-memory path ---

  void 'readContent returns content from in-memory FileUpload'() {
    given:
    def upload = inMemoryUpload('hello world')

    expect:
    NettyFileUploadContentReader.readContent(upload) == 'hello world'
  }

  void 'readContent truncates in-memory content at MAX_CONTENT_BYTES'() {
    given:
    def upload = inMemoryUpload('X' * inputSize)

    when:
    def result = NettyFileUploadContentReader.readContent(upload)

    then:
    result.length() == expectedSize

    where:
    inputSize                                              | expectedSize
    13                                                     | 13
    NettyFileUploadContentReader.MAX_CONTENT_BYTES - 1    | NettyFileUploadContentReader.MAX_CONTENT_BYTES - 1
    NettyFileUploadContentReader.MAX_CONTENT_BYTES        | NettyFileUploadContentReader.MAX_CONTENT_BYTES
    NettyFileUploadContentReader.MAX_CONTENT_BYTES + 500  | NettyFileUploadContentReader.MAX_CONTENT_BYTES
  }

  void 'readContent returns empty string for empty in-memory content'() {
    given:
    def upload = inMemoryUpload('')

    expect:
    NettyFileUploadContentReader.readContent(upload) == ''
  }

  void 'readContent does not advance readerIndex of the underlying ByteBuf'() {
    given:
    def content = 'sensitive data'
    def upload = inMemoryUpload(content)
    def buf = upload.getByteBuf()
    def indexBefore = buf.readerIndex()

    when:
    NettyFileUploadContentReader.readContent(upload)

    then:
    buf.readerIndex() == indexBefore
  }

  // --- disk-backed path ---

  void 'readContent returns content from disk-backed FileUpload'() {
    given:
    def upload = diskBackedUpload('hello from disk')

    expect:
    NettyFileUploadContentReader.readContent(upload) == 'hello from disk'
  }

  void 'readContent truncates disk-backed content at MAX_CONTENT_BYTES'() {
    given:
    def upload = diskBackedUpload('Y' * inputSize)

    when:
    def result = NettyFileUploadContentReader.readContent(upload)

    then:
    result.length() == expectedSize

    where:
    inputSize                                              | expectedSize
    13                                                     | 13
    NettyFileUploadContentReader.MAX_CONTENT_BYTES - 1    | NettyFileUploadContentReader.MAX_CONTENT_BYTES - 1
    NettyFileUploadContentReader.MAX_CONTENT_BYTES        | NettyFileUploadContentReader.MAX_CONTENT_BYTES
    NettyFileUploadContentReader.MAX_CONTENT_BYTES + 500  | NettyFileUploadContentReader.MAX_CONTENT_BYTES
  }

  void 'readContent returns empty string for empty disk-backed file'() {
    given:
    def upload = diskBackedUpload('')

    expect:
    NettyFileUploadContentReader.readContent(upload) == ''
  }

  // --- error handling ---

  void 'readContent returns empty string when getByteBuf throws'() {
    given:
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> true
    upload.getByteBuf() >> { throw new RuntimeException('simulated error') }

    expect:
    NettyFileUploadContentReader.readContent(upload) == ''
  }

  void 'readContent returns empty string when getFile throws'() {
    given:
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> false
    upload.getFile() >> { throw new IOException('simulated error') }

    expect:
    NettyFileUploadContentReader.readContent(upload) == ''
  }

  // --- helpers ---

  private FileUpload inMemoryUpload(String content) {
    def buf = Unpooled.copiedBuffer(content, StandardCharsets.ISO_8859_1)
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> true
    upload.getByteBuf() >> buf
    return upload
  }

  private FileUpload diskBackedUpload(String content) {
    def file = tempDir.resolve('upload.bin').toFile()
    file.bytes = content.getBytes(StandardCharsets.ISO_8859_1)
    FileUpload upload = Stub(FileUpload)
    upload.isInMemory() >> false
    upload.getFile() >> file
    return upload
  }
}
