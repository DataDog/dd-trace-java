package com.datadog.appsec.config

import spock.lang.Specification

import java.nio.charset.StandardCharsets

class AppSecConfigDeserializerSpecification extends Specification {

  void "deserialize rule with unknown key"() {
    given:
    final deser = AppSecConfigDeserializer.INSTANCE
    final input = """
    {
      "version": "2.9999",
      "metadata": {
        "rules_version": "1.7.1"
      },
      "exclusions": [
        {
          "UNKNOWN_FIELD": "UNKNOWN_VALUE"
        }
      ],
      "rules": [
        {
          "UNKNOWN_FIELD": "UNKNOWN_VALUE",
          "id": "blk-001-001",
          "name": "Block IP Addresses",
          "tags": {
            "type": "block_ip",
            "category": "security_response"
          },
          "conditions": [
            {
              "parameters": {
                "inputs": [
                  {
                    "address": "http.client_ip"
                  }
                ],
                "data": "blocked_ips"
              },
              "operator": "ip_match"
            }
          ],
          "transformers": [],
          "on_match": [
            "block"
          ]
        }
      ]
    }
    """

    when:
    def result = deser.deserialize(input.getBytes(StandardCharsets.UTF_8))

    then:
    result != null
    result.getNumberOfRules() == 1
  }
}
