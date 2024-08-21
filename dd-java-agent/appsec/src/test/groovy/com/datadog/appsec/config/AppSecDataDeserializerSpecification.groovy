package com.datadog.appsec.config

import spock.lang.Specification

import java.nio.charset.StandardCharsets

class AppSecDataDeserializerSpecification extends Specification {

  void "deserialize IP denylist"() {
    given:
    final deser = AppSecDataDeserializer.INSTANCE
    final input = """
{
  "rules_data": [
    {
      "data": [
        {
          "expiration": 0,
          "value": "1.1.1.1"
        },
        {
          "expiration": 1717083300,
          "value": "2.2.2.2"
        },
        {
          "expiration": 9223372036854775807,
          "value": "3.3.3.3"
        }
      ],
      "id": "blocked_ips",
      "type": "ip_with_expiration"
    }
  ]
}
    """

    when:
    def result = deser.deserialize(input.getBytes(StandardCharsets.UTF_8))

    then:
    result != null
    result.rules == [
      [
        data: [
          [
            expiration: 0.0,
            value     : "1.1.1.1"
          ],
          [
            expiration: 1717083300.0,
            value     : "2.2.2.2"
          ],
          [
            expiration: 9223372036854775807.0,
            value     : "3.3.3.3"
          ]
        ],
        id  : "blocked_ips",
        type: "ip_with_expiration"
      ]
    ]
  }

  void 'deserialize exclusions data'() {
    final deser = AppSecDataDeserializer.INSTANCE
    final input = """
{
  "exclusion_data": [
    {
      "id": "suspicious_ips_data_id",
      "type": "ip_with_expiration",
      "data": [
        {
          "value": "34.65.27.85"
        }
      ]
    }
  ]
}
    """

    when:
    def result = deser.deserialize(input.getBytes(StandardCharsets.UTF_8))

    then:
    result != null
    result.exclusion == [
      [
        id  : "suspicious_ips_data_id",
        type: "ip_with_expiration",
        data: [[
            value: "34.65.27.85"
          ]],

      ]
    ]
  }
}
