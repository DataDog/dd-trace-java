package com.datadog.iast.test

import org.junit.rules.ExternalResource
import org.slf4j.Logger

import java.lang.reflect.Field
import java.lang.reflect.Modifier

class ReplaceSlf4jLogger extends ExternalResource {

  private Field logField
  private Logger logger
  private Logger originalLogger

  ReplaceSlf4jLogger(final Field field, final Logger logger) {
    logField = field
    logField.accessible = true

    final modifiers = Field.getDeclaredField("modifiers")
    modifiers.accessible = true
    modifiers.setInt(logField, logField.getModifiers() & ~Modifier.FINAL)

    this.logger = logger
  }

  @Override
  protected void before() throws Throwable {
    originalLogger = (Logger) logField.get(null)
    logField.set(null, logger)
  }

  @Override
  protected void after() {
    logField.set(null, originalLogger)
  }
}
