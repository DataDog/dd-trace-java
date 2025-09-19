package datadog.trace.api.appsec


import spock.lang.Specification

class MediaTypeSpecification extends Specification {

  void 'test media type parsing'() {
    when:
    final media = MediaType.parse(header)

    then:
    media.type == type
    media.subtype == subtype
    media.charset == charset
    media.json == json

    where:
    header                                    | type          | subtype            | charset      | json
    // Standard JSON types
    'application/json'                        | 'application' | 'json'             | null         | true
    'text/json'                               | 'text'        | 'json'             | null         | true

    // JSON subtypes
    'application/vnd.api+json'                | 'application' | 'vnd.api+json'     | null         | true
    'application/ld+json'                     | 'application' | 'ld+json'          | null         | true
    'application/hal+json'                    | 'application' | 'hal+json'         | null         | true
    'application/problem+json'                | 'application' | 'problem+json'     | null         | true
    'application/merge-patch+json'            | 'application' | 'merge-patch+json' | null         | true
    'application/json-patch+json'             | 'application' | 'json-patch+json'  | null         | true

    // With parameters
    'application/json; charset=utf-8'         | 'application' | 'json'             | 'utf-8'      | true
    'application/json;charset=UTF-8'          | 'application' | 'json'             | 'utf-8'      | true
    'text/json; charset=iso-8859-1'           | 'text'        | 'json'             | 'iso-8859-1' | true
    'application/vnd.api+json; charset=utf-8' | 'application' | 'vnd.api+json'     | 'utf-8'      | true

    // Case variations
    'APPLICATION/JSON'                        | 'application' | 'json'             | null         | true
    'Text/Json'                               | 'text'        | 'json'             | null         | true
    'application/VND.API+JSON'                | 'application' | 'vnd.api+json'     | null         | true

    // With whitespace
    '  application/json  '                    | 'application' | 'json'             | null         | true
    'application/json ; charset=utf-8'        | 'application' | 'json'             | 'utf-8'      | true
    ' text/json; charset=utf-8 '              | 'text'        | 'json'             | 'utf-8'      | true

    // Non-JSON types
    'application/xml'                         | 'application' | 'xml'              | null         | false
    'text/html'                               | 'text'        | 'html'             | null         | false
    'text/plain'                              | 'text'        | 'plain'            | null         | false
    'application/pdf'                         | 'application' | 'pdf'              | null         | false
    'image/png'                               | 'image'       | 'png'              | null         | false
    'application/octet-stream'                | 'application' | 'octet-stream'     | null         | false

    // Edge cases
    ''                                        | ''            | null               | null         | false
    '   '                                     | ''            | null               | null         | false
    'json'                                    | 'json'        | null               | null         | false
    'application/'                            | 'application' | ''                 | null         | false
  }
}
