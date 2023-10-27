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
    def result = obfuscator.processTags(tags)

    then:
    assert result.get(DDTags.HTTP_QUERY) == expectedQuery
    assert result.get(Tags.HTTP_URL) == 'http://site.com/index?' + expectedQuery

    where:
    query                                                               | expectedQuery
    'key1=val1&token=a0b21ce2-006f-4cc6-95d5-d7b550698482&key2=val2'    | 'key1=val1&<redacted>&key2=val2'
    'app_key=1111&application_key=2222'                                 | '<redacted>&<redacted>'
  }
}
