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
    media.deserializable == deserializable

    where:
    header                                    | type          | subtype            | charset      | json  | deserializable
    // Standard JSON types
    'application/json'                        | 'application' | 'json'             | null         | true  | true
    'text/json'                               | 'text'        | 'json'             | null         | true  | true

    // JSON subtypes
    'application/vnd.api+json'                | 'application' | 'vnd.api+json'     | null         | true  | true
    'application/ld+json'                     | 'application' | 'ld+json'          | null         | true  | true
    'application/hal+json'                    | 'application' | 'hal+json'         | null         | true  | true
    'application/problem+json'                | 'application' | 'problem+json'     | null         | true  | true
    'application/merge-patch+json'            | 'application' | 'merge-patch+json' | null         | true  | true
    'application/json-patch+json'             | 'application' | 'json-patch+json'  | null         | true  | true

    // With parameters
    'application/json; charset=utf-8'         | 'application' | 'json'             | 'utf-8'      | true  | true
    'application/json;charset=UTF-8'          | 'application' | 'json'             | 'utf-8'      | true  | true
    'text/json; charset=iso-8859-1'           | 'text'        | 'json'             | 'iso-8859-1' | true  | true
    'application/vnd.api+json; charset=utf-8' | 'application' | 'vnd.api+json'     | 'utf-8'      | true  | true

    // Case variations
    'APPLICATION/JSON'                        | 'application' | 'json'             | null         | true  | true
    'Text/Json'                               | 'text'        | 'json'             | null         | true  | true
    'application/VND.API+JSON'                | 'application' | 'vnd.api+json'     | null         | true  | true

    // With whitespace
    '  application/json  '                    | 'application' | 'json'             | null         | true  | true
    'application/json ; charset=utf-8'        | 'application' | 'json'             | 'utf-8'      | true  | true
    ' text/json; charset=utf-8 '              | 'text'        | 'json'             | 'utf-8'      | true  | true

    // Non-JSON types
    'application/xml'                         | 'application' | 'xml'              | null         | false | false
    'text/html'                               | 'text'        | 'html'             | null         | false | false
    'text/plain'                              | 'text'        | 'plain'            | null         | false | false
    'application/pdf'                         | 'application' | 'pdf'              | null         | false | false
    'image/png'                               | 'image'       | 'png'              | null         | false | false
    'application/octet-stream'                | 'application' | 'octet-stream'     | null         | false | false

    // Edge cases
    null                                      | null          | null               | null         | false | false
    ''                                        | null          | null               | null         | false | false
    '   '                                     | null          | null               | null         | false | false
    'json'                                    | 'json'        | null               | null         | false | false
    'application/'                            | 'application' | null               | null         | false | false
  }
}
