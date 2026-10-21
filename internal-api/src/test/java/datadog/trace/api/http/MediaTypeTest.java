package datadog.trace.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class MediaTypeTest {

  @TableTest({
    "scenario                    | header                                    | type        | subtype          | charset    | json  | deserializable",
    // Standard JSON types
    "application json            | application/json                          | application | json             |            | true  | true          ",
    "text json                   | text/json                                 | text        | json             |            | true  | true          ",
    // JSON subtypes
    "vnd.api json                | application/vnd.api+json                  | application | vnd.api+json     |            | true  | true          ",
    "ld json                     | application/ld+json                       | application | ld+json          |            | true  | true          ",
    "hal json                    | application/hal+json                      | application | hal+json         |            | true  | true          ",
    "problem json                | application/problem+json                  | application | problem+json     |            | true  | true          ",
    "merge-patch json            | application/merge-patch+json              | application | merge-patch+json |            | true  | true          ",
    "json-patch json             | application/json-patch+json               | application | json-patch+json  |            | true  | true          ",
    // With parameters
    "charset with space          | 'application/json; charset=utf-8'         | application | json             | utf-8      | true  | true          ",
    "charset upper case          | 'application/json;charset=UTF-8'          | application | json             | utf-8      | true  | true          ",
    "text json iso charset       | 'text/json; charset=iso-8859-1'           | text        | json             | iso-8859-1 | true  | true          ",
    "vnd.api json with charset   | 'application/vnd.api+json; charset=utf-8' | application | vnd.api+json     | utf-8      | true  | true          ",
    // Case variations
    "upper case                  | APPLICATION/JSON                          | application | json             |            | true  | true          ",
    "capitalized                 | Text/Json                                 | text        | json             |            | true  | true          ",
    "upper case subtype          | application/VND.API+JSON                  | application | vnd.api+json     |            | true  | true          ",
    // With whitespace
    "surrounding whitespace      | '  application/json  '                    | application | json             |            | true  | true          ",
    "whitespace before parameter | 'application/json ; charset=utf-8'        | application | json             | utf-8      | true  | true          ",
    "whitespace with charset     | ' text/json; charset=utf-8 '              | text        | json             | utf-8      | true  | true          ",
    // Non-JSON types
    "xml                         | application/xml                           | application | xml              |            | false | false         ",
    "html                        | text/html                                 | text        | html             |            | false | false         ",
    "plain text                  | text/plain                                | text        | plain            |            | false | false         ",
    "pdf                         | application/pdf                           | application | pdf              |            | false | false         ",
    "png                         | image/png                                 | image       | png              |            | false | false         ",
    "octet-stream                | application/octet-stream                  | application | octet-stream     |            | false | false         ",
    // Edge cases
    "null header                 |                                           |             |                  |            | false | false         ",
    "empty header                | ''                                        |             |                  |            | false | false         ",
    "blank header                | '   '                                     |             |                  |            | false | false         ",
    "type only                   | json                                      | json        |                  |            | false | false         ",
    "empty subtype               | application/                              | application |                  |            | false | false         "
  })
  void mediaTypeParsing(
      String header,
      String type,
      String subtype,
      String charset,
      boolean json,
      boolean deserializable) {
    MediaType media = MediaType.parse(header);

    assertEquals(type, media.getType());
    assertEquals(subtype, media.getSubtype());
    assertEquals(charset, media.getCharset());
    assertEquals(json, media.isJson());
    assertEquals(deserializable, media.isDeserializable());
  }
}
