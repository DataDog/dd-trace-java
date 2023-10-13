package com.datadog.iast.model.json

import com.datadog.iast.model.Evidence
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import datadog.trace.api.config.IastConfig
import datadog.trace.test.util.DDSpecification
import org.skyscreamer.jsonassert.JSONAssert
import spock.lang.Shared

class EvidenceEncodingTest extends DDSpecification {

  private static final List<Source> SOURCES_SUITE = (0..2).collect { new Source((byte) it, "name$it", "value$it") }

  @Shared
  private JsonAdapter<Evidence> evidenceAdapter

  @Override
  void setup() {
    injectSysConfig(IastConfig.IAST_REDACTION_ENABLED, 'false')
    final context = new AdapterFactory.Context()
    SOURCES_SUITE.eachWithIndex { source, index ->
      context.sources.add(source)
      context.sourceIndexMap.put(source, index)
    }
    AdapterFactory.Context.set(context)

    evidenceAdapter = new Moshi.Builder()
      .add(new SourceTypeAdapter())
      .add(new AdapterFactory())
      .build()
      .adapter(Evidence)
  }

  void cleanup() {
    AdapterFactory.Context.remove()
  }

  void 'test build tainted evidence'(final String value, final List<Range> ranges, final String expected) {
    when:
    final evidence = new Evidence(value, ranges == null ? null : (Range[]) ranges.toArray(new Range[0]))
    final json = evidenceAdapter.toJson(evidence)

    then:
    if (expected == null) {
      json == null
    } else {
      JSONAssert.assertEquals(expected, json, true)
    }

    where:
    value                       | ranges                                           | expected
    null                        | null                                             | null
    null                        | []                                               | null
    'Hello World'               | []                                               | '{"value": "Hello World"}'
    'Hello World'               | null                                             | '{"value": "Hello World"}'
    'Hello World'               | [range(0, 5, source(0))]                         | '{"valueParts": [{"value": "Hello", "source": 0}, {"value": " World"}]}'
    'Hello World'               | [range(6, 5, source(0))]                         | '{"valueParts": [{"value": "Hello "}, {"value": "World", "source": 0}]}'
    'Hello World'               | [range(0, 5, source(0)), range(6, 5, source(1))] | '{"valueParts": [{"value": "Hello", "source": 0}, {"value": " "}, {"value": "World", "source": 1}]}'
    'Hello World'               | [range(0, 11, source(0))]                        | '{"valueParts": [{"value": "Hello World", "source": 0}]}'
    'Hello World'               | [range(5, 1, source(0))]                         | '{"valueParts": [{"value": "Hello"}, {"value": " ", "source": 0}, {"value": "World"}]}'
    'java.lang.Object@1cb991da' | [range(0, Integer.MAX_VALUE, source(0))]         | '{"valueParts": [{"value": "java.lang.Object@1cb991da", "source": 0}]}'
  }

  private static Range range(final int start, final int length, final Source source) {
    return new Range(start, length, source, Range.NOT_MARKED)
  }

  private static Source source(final int index) {
    return SOURCES_SUITE[index]
  }
}
