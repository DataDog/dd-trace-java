package com.datadog.appsec.config

import spock.lang.Specification

import java.util.function.Function

class MergedAsmDataSpecification extends Specification {
  void 'merge of data without expiration: #test'() {
    setup:
    def cfg1 = test.set.apply([
      [
        id: 'id1',
        type: 'type1',
        data: [[value: 'foobar1']]
      ],
      [
        id: 'id1',
        type: 'type1',
        data: [[value: 'foobar2']]
      ]
    ])
    def cfg2 = test.set.apply([
      [
        id: 'id2',
        type: 'type1',
        data: [[value: 'foobar4']]
      ],
      [
        id: 'id1',
        type: 'type1',
        data: [[value: 'foobar3']]
      ],
    ])
    def md = new MergedAsmData([cfg1: cfg1, cfg2: cfg2]).mergedData
    test.get.apply(md).sort({ a, b -> a['id'] <=> b['id'] })

    expect:
    test.get.apply(md) == [
      [
        id: 'id1',
        type: 'type1',
        data: [[value: 'foobar1'], [value: 'foobar2'], [value: 'foobar3'],]
      ],
      [
        id: 'id2',
        type: 'type1',
        data: [[value: 'foobar4']]
      ]
    ]

    where:
    test << testSuite()
  }

  void 'merge of data with expiration: #test'() {
    setup:
    def cfg1 =  test.set.apply([
      [
        id: 'id1',
        type: 'data_with_expiration',
        data: [
          [value: 'foobar1', expiration: 20],
          [value: 'foobar1', expiration: 5],
        ]
      ],
      [
        id: 'id1',
        type: 'data_with_expiration',
        data: [[value: 'foobar1', expiration: 50], [value: 'foobar2'],]
      ]
    ])
    def cfg2 = test.set.apply([
      [
        id: 'id1',
        type: 'data_with_expiration',
        data: [[value: 'foobar2', expiration: 100]]
      ],
    ])
    def md = new MergedAsmData([cfg1: cfg1, cfg2: cfg2]).mergedData
    test.get.apply(md).sort({ a, b -> a['id'] <=> b['id'] })

    expect:
    test.get.apply(md) == [
      [
        id: 'id1',
        type: 'data_with_expiration',
        data: [[value: 'foobar2'], [value: 'foobar1', expiration: 50]]
      ]
    ]

    where:
    test << testSuite()
  }

  void 'error due to mismatched type: #test'() {
    when:
    new MergedAsmData([
      cfg: test.set.apply([
        [
          id: 'foo',
          // type: null,
          data: []
        ],
        [
          'id': 'foo',
          type: 'bar',
          data: []
        ]
      ])
    ]).mergedData
    then:
    thrown MergedAsmData.InvalidAsmDataException

    where:
    test << testSuite()
  }

  private static List<Test> testSuite() {
    return [
      new Test(descr: 'rules_data', set: { payload -> new AppSecData(rules: payload) }, get: { AppSecData data -> data.rules }),
      new Test(descr: 'exclusion_data', set: { payload -> new AppSecData(exclusion: payload) }, get: { AppSecData data -> data.exclusion })
    ]
  }

  private static class Test {
    String descr
    Function<List<Map<String, Object>>, AppSecData> set
    Function<AppSecData, List<Map<String, Object>>> get

    @Override
    String toString() {
      return descr
    }
  }
}
