package com.fasterxml.jackson.databind.node

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializerProvider
import java.io.IOException

class NullTextJsonNode extends BaseJsonNode {
  @Override
  JsonNodeType getNodeType() {
    return JsonNodeType.STRING
  }

  @Override
  String textValue() {
    return null
  }

  @Override
  JsonToken asToken() {
    return JsonToken.VALUE_STRING
  }

  @Override
  void serialize(JsonGenerator gen, SerializerProvider serializers) throws IOException {
    gen.writeNull()
  }

  @Override
  JsonNode deepCopy() {
    return this
  }
}
