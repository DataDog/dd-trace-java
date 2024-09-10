package com.fasterxml.jackson.core.sym;

public final class ByteQuadsCanonicalizerHelper {
  private ByteQuadsCanonicalizerHelper() {}

  public static boolean fetchInterned(ByteQuadsCanonicalizer symbols) {
    return symbols._intern;
  }
}
