package com.datadog.debugger.symbol;

import datadog.config.util.Strings;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolExtractionTransformer implements ClassFileTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolExtractionTransformer.class);

  private final SymbolAggregator symbolAggregator;
  private final ClassNameFilter classNameFiltering;

  public SymbolExtractionTransformer(
      SymbolAggregator symbolAggregator, ClassNameFilter classNameFiltering) {
    this.symbolAggregator = symbolAggregator;
    this.classNameFiltering = classNameFiltering;
  }

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (className == null) {
      return null;
    }
    try {
      if (className.startsWith("com/datadog/debugger/symbol/")) {
        // Don't parse our own classes to avoid duplicate class definition
        return null;
      }
      if (classNameFiltering.isExcluded(Strings.getClassName(className))) {
        return null;
      }
      symbolAggregator.parseClass(className, classfileBuffer, protectionDomain);
      return null;
    } catch (Exception ex) {
      LOGGER.debug("Error during extraction: ", ex);
      return null;
    }
  }

  ClassNameFilter getClassNameFiltering() {
    return classNameFiltering;
  }
}
