package datadog.trace.core.tagprocessor

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.DDSpecification

class QueryObfuscatorTest extends DDSpecification {
  def "tags processing"() {
    setup:
    def obfuscator = new QueryObfuscator()
    def tags = [
      (Tags.HTTP_URL): 'http://site.com/index',
      (DDTags.HTTP_QUERY): query
    ]

    when:
    def result = obfuscator.processTags(tags, null)

    then:
    assert result.get(DDTags.HTTP_QUERY) == expectedQuery
    assert result.get(Tags.HTTP_URL) == 'http://site.com/index?' + expectedQuery

    where:
    query                                                               | expectedQuery
    'key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2'    | 'key1=val1&<redacted>&key2=val2'
    'app_key=1111&application_key=2222'                                 | '<redacted>&<redacted>'
    'email=foo@bar.com'                                                 | 'email=foo@bar.com'
  }

  def "tags processing with custom regexp for email"() {
    setup:
    def obfuscator = new QueryObfuscator("(?i)(?:(?:\"|%22)?)(?:(?:old[-_]?|new[-_]?)?p(?:ass)?w(?:or)?d(?:1|2)?|pass(?:[-_]?phrase)?|email|secret|(?:api[-_]?|private[-_]?|public[-_]?|access[-_]?|secret[-_]?|app(?:lication)?[-_]?)key(?:[-_]?id)?|token|consumer[-_]?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)(?:(?:\\s|%20)*(?:=|%3D)[^&]+|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22))|(?:bearer(?:\\s|%20)+[a-z0-9._\\-]+|token(?::|%3A)[a-z0-9]{13}|gh[opsu]_[0-9a-zA-Z]{36}|ey[I-L](?:[\\w=-]|%3D)+\\.ey[I-L](?:[\\w=-]|%3D)+(?:\\.(?:[\\w.+/=-]|%3D|%2F|%2B)+)?|-{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY-{5}[^\\-]+-{5}END(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY(?:-{5})?(?:\\n|%0A)?|(?:ssh-(?:rsa|dss)|ecdsa-[a-z0-9]+-[a-z0-9]+)(?:\\s|%20|%09)+(?:[a-z0-9/.+]|%2F|%5C|%2B){100,}(?:=|%3D)*(?:(?:\\s|%20|%09)+[a-z0-9._-]+)?)")
    def tags = [
      (Tags.HTTP_URL): 'http://site.com/index',
      (DDTags.HTTP_QUERY): query
    ]

    when:
    def result = obfuscator.processTags(tags, null)

    then:
    assert result.get(DDTags.HTTP_QUERY) == expectedQuery
    assert result.get(Tags.HTTP_URL) == 'http://site.com/index?' + expectedQuery

    where:
    query                                                               | expectedQuery
    'key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2'    | 'key1=val1&<redacted>&key2=val2'
    'app_key=1111&application_key=2222'                                 | '<redacted>&<redacted>'
    'email=foo@bar.com'                                                 | '<redacted>'
  }
}
