import datadog.trace.instrumentation.commons.fileupload.FileItemContentReader
import org.apache.commons.fileupload.FileItem
import spock.lang.Specification

class FileItemContentReaderTest extends Specification {

  void 'readContent truncates at MAX_CONTENT_BYTES'() {
    given:
    def item = fileItem('X' * inputSize)

    when:
    def result = FileItemContentReader.readContent(item)

    then:
    result.length() == expectedSize

    where:
    inputSize                                     | expectedSize
    13                                            | 13
    FileItemContentReader.MAX_CONTENT_BYTES - 1   | FileItemContentReader.MAX_CONTENT_BYTES - 1
    FileItemContentReader.MAX_CONTENT_BYTES       | FileItemContentReader.MAX_CONTENT_BYTES
    FileItemContentReader.MAX_CONTENT_BYTES + 500 | FileItemContentReader.MAX_CONTENT_BYTES
  }

  void 'readContent returns empty string when getInputStream throws'() {
    given:
    FileItem item = Stub(FileItem)
    item.getInputStream() >> { throw new IOException('simulated error') }

    expect:
    FileItemContentReader.readContent(item) == ''
  }

  void 'readContent returns empty string for empty content'() {
    given:
    def item = fileItem('')

    expect:
    FileItemContentReader.readContent(item) == ''
  }

  void 'readContent uses Content-Type from file item for charset decoding'() {
    given:
    def text = 'héllo wörld'
    def item = fileItemFromBytes(text.getBytes('UTF-8'), 'file.txt', 'text/plain; charset=UTF-8')

    expect:
    FileItemContentReader.readContent(item) == text
  }

  private FileItem fileItem(String content) {
    fileItem(content, 'file.txt')
  }

  private FileItem fileItem(String content, String name) {
    fileItem(content, name, null)
  }

  private FileItem fileItem(String content, String name, String contentType) {
    fileItemFromBytes((content ?: '').getBytes('ISO-8859-1'), name, contentType)
  }

  private FileItem fileItemFromBytes(byte[] bytes, String name, String contentType) {
    FileItem item = Stub(FileItem)
    item.isFormField() >> false
    item.getName() >> name
    item.getContentType() >> contentType
    item.getInputStream() >> new ByteArrayInputStream(bytes)
    return item
  }
}
