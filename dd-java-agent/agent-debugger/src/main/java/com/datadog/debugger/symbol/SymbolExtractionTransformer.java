package com.datadog.debugger.symbol;

import com.datadog.debugger.util.ClassNameFiltering;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolExtractionTransformer implements ClassFileTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolExtractionTransformer.class);

  private final SymbolAggregator symbolAggregator;
  private final ClassNameFiltering classNameFiltering;

  public SymbolExtractionTransformer(
      SymbolAggregator symbolAggregator, ClassNameFiltering classNameFiltering) {
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
      if (classNameFiltering.isExcluded(className)) {
        return null;
      }
      symbolAggregator.parseClass(className, classfileBuffer, protectionDomain);
      return null;
    } catch (Exception ex) {
      LOGGER.debug("Error during extraction: ", ex);
      return null;
    }
  }

  ClassNameFiltering getClassNameFiltering() {
    return classNameFiltering;
  }
}
