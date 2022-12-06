package com.datadog.appsec.config

import spock.lang.Specification

class MergedAsmDataSpecification extends Specification {
  void 'merge of data without expiration'() {
    setup:
    def cfg1 = [
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
    ]
    def cfg2 = [
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
    ]
    def md = new MergedAsmData([cfg1: cfg1, cfg2: cfg2]).mergedData
    md.sort({ a, b -> a['id'] <=> b['id'] })

    expect:
    md == [
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
  }

  void 'merge of data with expiration'() {
    setup:
    def cfg1 = [
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
    ]
    def cfg2 = [
      [
        id: 'id1',
        type: 'data_with_expiration',
        data: [[value: 'foobar2', expiration: 100]]
      ],
    ]
    def md = new MergedAsmData([cfg1: cfg1, cfg2: cfg2]).mergedData
    md.sort({ a, b -> a['id'] <=> b['id'] })

    expect:
    md == [
      [
        id: 'id1',
        type: 'data_with_expiration',
        data: [[value: 'foobar2'], [value: 'foobar1', expiration: 50]]
      ]
    ]
  }

  void 'error due to mismatched type'() {
    when:
    new MergedAsmData([
      cfg: [
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
      ]
    ]).mergedData
    then:
    thrown MergedAsmData.InvalidAsmDataException
  }
}
