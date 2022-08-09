package datadog.trace.core.tagprocessor

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.DDSpecification

class QueryObfuscatorTest extends DDSpecification {

  final url = 'http://site.com/index'
  QueryObfuscator obfuscator = new QueryObfuscator(null)

  def "disabled obfuscator"() {
    given:
    QueryObfuscator obfuscator = new QueryObfuscator("")
    def tags = [
      (Tags.HTTP_URL)    : url,
      (DDTags.HTTP_QUERY): input
    ]

    when:
    def result = obfuscator.processTags(tags)

    then:
    result.get(DDTags.HTTP_QUERY) == expected
    result.get(Tags.HTTP_URL) == "${url}?${expected}"

    where:
    input               | expected
    'access_key=sekrit' | 'access_key=sekrit'
  }

  def "redact query parameters"() {
    setup:
    String input = null
    String expected = null
    switch (position) {
      case 'single':
        input = query
        expected = "<redacted>"
        break
      case 'start':
        input = "${query}&key1=value1"
        expected = "<redacted>&key1=value1"
        break
      case 'middle':
        input = "key1=value1&${query}&key2=value2"
        expected = "key1=value1&<redacted>&key2=value2"
        break
      case 'end':
        input = "key1=value1&${query}"
        expected = "key1=value1&<redacted>"
        break
      default:
        throw new RuntimeException('Invalid position')
    }
    def tags = [
      (Tags.HTTP_URL)    : url,
      (DDTags.HTTP_QUERY): input
    ]

    when:
    def result = obfuscator.processTags(tags)

    then:
    result.get(DDTags.HTTP_QUERY) == expected
    result.get(Tags.HTTP_URL) == "${url}?${expected}"

    where:
    [query, position] << [
      [
        'access_key=sekrit',
        'accesskey=sekrit',
        'access_key_id=sekrit',
        'access_keyid=sekrit',
        'accesskey_id=sekrit',
        'accesskeyid=sekrit',
        'api_key_id=sekrit',
        'api_keyid=sekrit',
        'apikey_id=sekrit',
        'apikeyid=sekrit',
        'auth=sekrit',
        'authentication=sekrit',
        'authorization=sekrit',
        'Authorization=Bearer 00D2w000000kc19',
        'consumer_key=sekrit',
        'consumerkey=sekrit',
        'consumer_id=sekrit',
        'consumerid=sekrit',
        'consumer_secret=sekrit',
        'consumersecret=sekrit',
        'pass=sekrit',
        'pass%3Dsekrit',
        'pass =sekrit',
        'pass  =  sekrit',
        'pass%20=%20sekrit',
        'pass%20=%20sekrit%20',
        'pass_phrase=sekrit',
        'passphrase=sekrit',
        'passwd=sekrit',
        'password=sekrit',
        'private_key_id=sekrit',
        'private_keyid=sekrit',
        'privatekey_id=sekrit',
        'privatekeyid=sekrit',
        'public_key_id=sekrit',
        'public_keyid=sekrit',
        'publickey_id=sekrit',
        'publickeyid=sekrit',
        'pword=sekrit',
        'pwd=sekrit',
        'sign=sekrit',
        'signature=sekrit',
        'signed=sekrit',
        'token=a0b21ce2-006f-4cc6-95d5-d7b550698482'
      ],
      ['single', 'start', 'end', 'middle']
    ].combinations()
  }

  def "redact query strings"() {
    setup:
    final url = 'http://site.com/index'
    def tags = [
      (Tags.HTTP_URL)    : url,
      (DDTags.HTTP_QUERY): input
    ]

    when:
    def result = obfuscator.processTags(tags)

    then:
    result.get(DDTags.HTTP_QUERY) == expected
    result.get(Tags.HTTP_URL) == "${url}?${expected}"

    where:
    input                                                                                                                                  | expected
    'json=%7B%20%22sign%22%3A%20%22%7B0x03cb9f67%2C0xdbbc%2C0x4cb8%2C%7B0xb9%2C0x66%2C0x32%2C0x99%2C0x51%2C0xe1%2C0x09%2C0x34%7D%7D%22%7D' | 'json=%7B%20%22<redacted>%7D'
    'json="token":"sekrit"'                                                                                                                | 'json="<redacted>'
    'json="token":"sekrit"&key=val'                                                                                                        | 'json="<redacted>&key=val'
    'itoken=sekrit'                                                                                                                        | 'i<redacted>'
    'custom=Bearer 00D2w000000kc19'                                                                                                        | 'custom=<redacted>'
  }

  Map<String, String> prepareTags(String query) {
    [
      (Tags.HTTP_URL)    : 'http://site.com/index',
      (DDTags.HTTP_QUERY): query
    ]
  }
}
