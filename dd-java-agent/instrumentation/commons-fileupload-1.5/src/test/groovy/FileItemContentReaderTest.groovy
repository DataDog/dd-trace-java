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

  void 'readContent decodes UTF-8 when Content-Type specifies charset=UTF-8'() {
    given:
    def text = 'héllo wörld'
    def item = fileItemFromBytes(text.getBytes('UTF-8'), 'file.txt', 'text/plain; charset=UTF-8')

    expect:
    FileItemContentReader.readContent(item) == text
  }

  void 'readContent falls back to UTF-8 when Content-Type has no charset'() {
    given:
    def text = 'hello world'
    def item = fileItemFromBytes(text.getBytes('UTF-8'), 'file.txt', 'text/plain')

    expect:
    FileItemContentReader.readContent(item) == text
  }

  void 'readContent falls back to UTF-8 when Content-Type is null'() {
    given:
    def text = 'hello world'
    def item = fileItemFromBytes(text.getBytes('UTF-8'), 'file.txt', null)

    expect:
    FileItemContentReader.readContent(item) == text
  }

  void 'readContent falls back to ISO-8859-1 when bytes are invalid UTF-8'() {
    given:
    // 0xE9 is 'é' in ISO-8859-1 but an invalid lone UTF-8 byte
    byte[] iso88591Bytes = 'café'.getBytes('ISO-8859-1')
    def item = fileItemFromBytes(iso88591Bytes, 'file.txt', null)

    expect:
    FileItemContentReader.readContent(item) == 'café'
  }

  void 'readContents returns content for each non-form file with a name'() {
    given:
    def items = [fileItem('content-a', 'file-a.txt'), fileItem('content-b', 'file-b.txt'),]

    when:
    def result = FileItemContentReader.readContents(items)

    then:
    result == ['content-a', 'content-b']
  }

  void 'readContents skips form fields'() {
    given:
    FileItem formField = Stub(FileItem)
    formField.isFormField() >> true
    def items = [formField, fileItem('content', 'real.txt')]

    when:
    def result = FileItemContentReader.readContents(items)

    then:
    result == ['content']
  }

  void 'readContents includes file parts with empty or null name'() {
    given:
    def items = [
      fileItem('content-no-name', null),
      fileItem('content-empty-name', ''),
      fileItem('content-named', 'named.txt'),
    ]

    when:
    def result = FileItemContentReader.readContents(items)

    then:
    result == ['content-no-name', 'content-empty-name', 'content-named']
  }

  void 'readContents stops after MAX_FILES_TO_INSPECT files'() {
    given:
    def items = (1..FileItemContentReader.MAX_FILES_TO_INSPECT + 1).collect {
      fileItem("content-${it}", "file-${it}.txt")
    }

    when:
    def result = FileItemContentReader.readContents(items)

    then:
    result.size() == FileItemContentReader.MAX_FILES_TO_INSPECT
  }

  void 'readContents returns empty list for empty input'() {
    expect:
    FileItemContentReader.readContents([]) == []
  }

  void 'extractCharset returns null for null contentType'() {
    expect:
    FileItemContentReader.extractCharset(null) == null
  }

  void 'extractCharset returns null for contentType without charset'() {
    expect:
    FileItemContentReader.extractCharset('text/plain') == null
    FileItemContentReader.extractCharset('image/jpeg') == null
    FileItemContentReader.extractCharset('application/octet-stream') == null
  }

  void 'extractCharset returns null for invalid charset name'() {
    expect:
    FileItemContentReader.extractCharset('text/plain; charset=NOTACHARSET') == null
  }

  void 'extractCharset extracts charset case-insensitively'() {
    expect:
    FileItemContentReader.extractCharset('text/plain; CHARSET=UTF-8').name() == 'UTF-8'
    FileItemContentReader.extractCharset('text/plain; Charset=UTF-8').name() == 'UTF-8'
    FileItemContentReader.extractCharset('text/plain; charset=utf-8').name() == 'UTF-8'
  }

  void 'extractCharset extracts charset from standard Content-Type'() {
    expect:
    FileItemContentReader.extractCharset('text/plain; charset=UTF-8').name() == 'UTF-8'
    FileItemContentReader.extractCharset('text/xml; charset=ISO-8859-1').name() == 'ISO-8859-1'
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
