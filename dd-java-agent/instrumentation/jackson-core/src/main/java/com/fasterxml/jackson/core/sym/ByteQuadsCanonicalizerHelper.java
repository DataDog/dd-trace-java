package com.fasterxml.jackson.core.sym;

import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ByteQuadsCanonicalizerHelper {
  private ByteQuadsCanonicalizerHelper() {}

  private static final Logger log = LoggerFactory.getLogger(ByteQuadsCanonicalizerHelper.class);

  private static final Field INTERN = prepareIntern();
  private static final Field INTERNER = prepareInterner();

  private static Field prepareIntern() {
    try {
      return ByteQuadsCanonicalizer.class.getDeclaredField("_intern");
    } catch (Throwable e) {
      log.debug("Failed to get ByteQuadsCanonicalizer _intern field", e);
      return null;
    }
  }

  private static Field prepareInterner() {
    try {
      return ByteQuadsCanonicalizer.class.getDeclaredField("_interner");
    } catch (Throwable e) {
      log.debug("Failed to get ByteQuadsCanonicalizer _interner field", e);
      return null;
    }
  }

  public static boolean fetchInterned(ByteQuadsCanonicalizer symbols) {
    if (INTERN != null) {
      try {
        return (boolean) INTERN.get(symbols);
      } catch (IllegalAccessException e) {
        throw new UndeclaredThrowableException(e);
      }
    } else {
      if (INTERNER == null) {
        return false;
      }
      try {
        return INTERNER.get(symbols) != null;
      } catch (IllegalAccessException e) {
        throw new UndeclaredThrowableException(e);
      }
    }
  }
}
