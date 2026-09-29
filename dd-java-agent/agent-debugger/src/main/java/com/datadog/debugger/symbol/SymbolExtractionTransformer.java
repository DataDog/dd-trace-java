package com.datadog.debugger.symbol;

import static com.datadog.debugger.util.DebuggerInternalPackages.isDebuggerInternalClass;

import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.util.Strings;
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
    if (isDebuggerInternalClass(null)) {
      // Force DebuggerInternalPAckages to be loaded before calling it into the transform method
      // avoid LinkageError for duplicated class definition
      throw new IllegalArgumentException("DebuggerInternalClass should be loaded");
    }
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
      if (isDebuggerInternalClass(className)) {
        // Don't parse debugger-internal classes to avoid duplicate class definition
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
