import datadog.trace.instrumentation.commons.fileupload.FileItemContentReader
import org.apache.commons.fileupload.FileItem
import spock.lang.Specification

class CommonsFileUploadAppSecModuleTest extends Specification {

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

  void 'readContents returns content for each non-form file with a name'() {
    given:
    def items = [
      fileItem('content-a', 'file-a.txt'),
      fileItem('content-b', 'file-b.txt'),
    ]

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

  void 'readContents skips files with null or empty name'() {
    given:
    def items = [
      fileItem('content-no-name', null),
      fileItem('content-empty-name', ''),
      fileItem('content-named', 'named.txt'),
    ]

    when:
    def result = FileItemContentReader.readContents(items)

    then:
    result == ['content-named']
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

  private FileItem fileItem(String content) {
    fileItem(content, 'file.txt')
  }

  private FileItem fileItem(String content, String name) {
    FileItem item = Stub(FileItem)
    item.isFormField() >> false
    item.getName() >> name
    item.getInputStream() >> new ByteArrayInputStream((content ?: '').getBytes('ISO-8859-1'))
    return item
  }
}
