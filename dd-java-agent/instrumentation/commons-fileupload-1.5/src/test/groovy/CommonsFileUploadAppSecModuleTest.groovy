import datadog.trace.instrumentation.commons.fileupload.CommonsFileUploadAppSecModule
import org.apache.commons.fileupload.FileItem
import spock.lang.Specification

class CommonsFileUploadAppSecModuleTest extends Specification {

  def "readContent returns full content when smaller than limit"() {
    given:
    def content = 'Hello, World!'
    def item = fileItem(content)

    expect:
    CommonsFileUploadAppSecModule.ParseRequestAdvice.readContent(item) == content
  }

  def "readContent truncates content to MAX_FILE_CONTENT_BYTES"() {
    given:
    def largeContent = 'X' * (CommonsFileUploadAppSecModule.ParseRequestAdvice.MAX_FILE_CONTENT_BYTES + 500)
    def item = fileItem(largeContent)

    when:
    def result = CommonsFileUploadAppSecModule.ParseRequestAdvice.readContent(item)

    then:
    result.length() == CommonsFileUploadAppSecModule.ParseRequestAdvice.MAX_FILE_CONTENT_BYTES
    result == 'X' * CommonsFileUploadAppSecModule.ParseRequestAdvice.MAX_FILE_CONTENT_BYTES
  }

  def "readContent returns empty string when getInputStream throws"() {
    given:
    FileItem item = Stub(FileItem)
    item.getInputStream() >> { throw new IOException('simulated error') }

    expect:
    CommonsFileUploadAppSecModule.ParseRequestAdvice.readContent(item) == ''
  }

  def "readContent returns empty string for empty content"() {
    given:
    def item = fileItem('')

    expect:
    CommonsFileUploadAppSecModule.ParseRequestAdvice.readContent(item) == ''
  }

  def "readContent reads exactly MAX_FILE_CONTENT_BYTES when content equals the limit"() {
    given:
    def content = 'A' * CommonsFileUploadAppSecModule.ParseRequestAdvice.MAX_FILE_CONTENT_BYTES
    def item = fileItem(content)

    when:
    def result = CommonsFileUploadAppSecModule.ParseRequestAdvice.readContent(item)

    then:
    result.length() == CommonsFileUploadAppSecModule.ParseRequestAdvice.MAX_FILE_CONTENT_BYTES
    result == content
  }

  private FileItem fileItem(String content) {
    FileItem item = Stub(FileItem)
    item.getInputStream() >> new ByteArrayInputStream(content.getBytes('ISO-8859-1'))
    return item
  }
}
