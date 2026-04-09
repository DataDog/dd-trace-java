package datadog.trace.instrumentation.jetty8

import spock.lang.Specification
import spock.lang.Unroll

class MultipartHelperTest extends Specification {

  @Unroll
  def "filenameFromContentDisposition: #description"() {
    expect:
    MultipartHelper.filenameFromContentDisposition(cd) == expected

    where:
    description                          | cd                                                        | expected
    'null input'                         | null                                                      | null
    'no filename token'                  | 'form-data; name="upload"'                                | null
    'quoted filename'                    | 'form-data; name="upload"; filename="photo.jpg"'          | 'photo.jpg'
    'unquoted filename'                  | 'form-data; name="upload"; filename=photo.jpg'            | 'photo.jpg'
    'filename with spaces'               | 'form-data; filename="my file.jpg"'                       | 'my file.jpg'
    'empty quoted filename'              | 'form-data; name="f"; filename=""'                        | null
    'empty unquoted filename'            | 'form-data; name="f"; filename='                          | null
    'whitespace-only unquoted filename'  | 'form-data; name="f"; filename=  '                        | null
    'filename first token'               | 'filename="evil.php"'                                     | 'evil.php'
    'extra spaces around semicolons'     | 'form-data ;  name="f" ;  filename="a.txt" '              | 'a.txt'
    'filename with dots and dashes'      | 'form-data; filename="my-file.v2.tar.gz"'                 | 'my-file.v2.tar.gz'
    'stops at first filename= token'     | 'form-data; filename="first.jpg"; filename="second.jpg"'  | 'first.jpg'
  }
}
