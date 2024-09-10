package com.fasterxml.jackson.core.json;

import com.fasterxml.jackson.core.sym.ByteQuadsCanonicalizerHelper;

public final class Json2ParserHelper {
  private Json2ParserHelper() {}

  public static boolean fetchInterned(UTF8StreamJsonParser jsonParser) {
    return ByteQuadsCanonicalizerHelper.fetchInterned(jsonParser._symbols);
  }
}
