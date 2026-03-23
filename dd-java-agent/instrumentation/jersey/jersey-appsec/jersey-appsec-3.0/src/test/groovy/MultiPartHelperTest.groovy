import datadog.trace.instrumentation.jersey3.MultiPartHelper
import org.glassfish.jersey.media.multipart.FormDataBodyPart
import org.glassfish.jersey.media.multipart.FormDataContentDisposition
import spock.lang.Specification

import jakarta.ws.rs.core.MediaType

class MultiPartHelperTest extends Specification {

  // filenameFromBodyPart

  def "returns null when content disposition is null"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getFormDataContentDisposition() >> null

    expect:
    MultiPartHelper.filenameFromBodyPart(bodyPart) == null
  }

  def "returns null when filename is null or empty"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> rawFilename
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getFormDataContentDisposition() >> cd

    expect:
    MultiPartHelper.filenameFromBodyPart(bodyPart) == null

    where:
    rawFilename << [null, '']
  }

  def "extracts filename"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> filename
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getFormDataContentDisposition() >> cd

    expect:
    MultiPartHelper.filenameFromBodyPart(bodyPart) == filename

    where:
    filename << ['report.php', 'upload.txt', 'shell;evil.php', 'file"name.php']
  }

  // collectBodyPart — body map

  def "text/plain part is added to body map"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'field'
    bodyPart.getValue() >> 'value'
    bodyPart.getFormDataContentDisposition() >> null
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null)

    then:
    map == [field: ['value']]
  }

  def "non-text/plain part is not added to body map"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> null
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null)

    then:
    map.isEmpty()
  }

  def "null body map is skipped without error"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getFormDataContentDisposition() >> null

    expect:
    MultiPartHelper.collectBodyPart(bodyPart, null, null)
  }

  def "multiple values for same field are accumulated"() {
    given:
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'tag'
    bodyPart.getValue() >>> ['a', 'b']
    bodyPart.getFormDataContentDisposition() >> null
    def map = [:]

    when:
    MultiPartHelper.collectBodyPart(bodyPart, map, null)
    MultiPartHelper.collectBodyPart(bodyPart, map, null)

    then:
    map == [tag: ['a', 'b']]
  }

  // collectBodyPart — filenames

  def "filename is added to list when present"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> 'report.php'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.APPLICATION_OCTET_STREAM_TYPE
    bodyPart.getFormDataContentDisposition() >> cd
    def filenames = []

    when:
    MultiPartHelper.collectBodyPart(bodyPart, null, filenames)

    then:
    filenames == ['report.php']
  }

  def "null filenames list is skipped without error"() {
    given:
    def cd = Mock(FormDataContentDisposition)
    cd.getFileName() >> 'report.php'
    def bodyPart = Mock(FormDataBodyPart)
    bodyPart.getMediaType() >> MediaType.TEXT_PLAIN_TYPE
    bodyPart.getName() >> 'f'
    bodyPart.getValue() >> 'v'
    bodyPart.getFormDataContentDisposition() >> cd

    expect:
    MultiPartHelper.collectBodyPart(bodyPart, [:], null)
  }
}
