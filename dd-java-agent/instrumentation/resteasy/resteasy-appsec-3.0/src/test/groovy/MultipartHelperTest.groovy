import datadog.trace.instrumentation.resteasy.MultipartHelper
import spock.lang.Specification

class MultipartHelperTest extends Specification {

  def "returns null when no filename parameter"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == null

    where:
    cd << [
      'form-data',
      'form-data; name="field"',
      'form-data; name="field"; other=value',
      '',
    ]
  }

  def "extracts unquoted filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                          | expected
    'form-data; filename=report.php'            | 'report.php'
    'form-data; name="f"; filename=upload.txt'  | 'upload.txt'
    'attachment; filename=file.tar.gz'          | 'file.tar.gz'
  }

  def "extracts quoted filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                             | expected
    'form-data; filename="report.php"'             | 'report.php'
    'form-data; name="f"; filename="upload.txt"'   | 'upload.txt'
  }

  def "handles semicolons inside quoted filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                               | expected
    'form-data; filename="report;.php"'              | 'report;.php'
    'form-data; name="f"; filename="a;b;c.php"'      | 'a;b;c.php'
    'form-data; filename="shell;evil.php"'            | 'shell;evil.php'
  }

  def "handles escaped quotes inside filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition('form-data; filename="file\\"name.php"') == 'file"name.php'
  }

  def "returns null for empty filename value"() {
    expect:
    MultipartHelper.filenameFromContentDisposition('form-data; filename=""') == null
    MultipartHelper.filenameFromContentDisposition('form-data; filename=') == null
  }

  def "is case-insensitive for the filename parameter name"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == 'report.php'

    where:
    cd << [
      'form-data; FILENAME="report.php"',
      'form-data; Filename="report.php"',
      'form-data; fileName="report.php"',
    ]
  }

  def "handles MIME linear whitespace (tab) after semicolon"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                                        | expected
    'form-data; name="f";\tfilename="evil.php"'              | 'evil.php'
    'form-data;\tfilename="evil.php"'                        | 'evil.php'
    'form-data; name="f";\t\tfilename="evil.php"'            | 'evil.php'
  }

  def "handles optional whitespace around the equals sign"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    cd                                                        | expected
    'form-data; filename ="report.php"'                      | 'report.php'
    'form-data; filename= "report.php"'                      | 'report.php'
    'form-data; filename = "report.php"'                     | 'report.php'
    'form-data; filename\t=\t"report.php"'                   | 'report.php'
    'form-data; name="f";\tfilename\t=\t"evil.php"'          | 'evil.php'
  }

  def "does not match filename* extended parameter as filename"() {
    expect:
    MultipartHelper.filenameFromContentDisposition("form-data; filename*=UTF-8''evil.php") == null
  }
}
