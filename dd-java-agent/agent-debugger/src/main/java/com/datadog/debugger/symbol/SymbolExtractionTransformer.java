package com.datadog.debugger.symbol;

import com.datadog.debugger.agent.AllowListHelper;
import com.datadog.debugger.sink.SymbolSink;
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolExtractionTransformer implements ClassFileTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolExtractionTransformer.class);

  private final AllowListHelper allowListHelper;
  private final SymbolAggregator symbolAggregator;

  public SymbolExtractionTransformer() {
    this(
        new AllowListHelper(null),
        new SymbolAggregator(
            new SymbolSink(Config.get()), Config.get().getDebuggerSymbolFlushThreshold()));
  }

  public SymbolExtractionTransformer(
      AllowListHelper allowListHelper, SymbolAggregator symbolAggregator) {
    this.allowListHelper = allowListHelper;
    this.symbolAggregator = symbolAggregator;
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
      if (allowListHelper.isAllowAll()) {
        if (className.startsWith("java/")
            || className.startsWith("javax/")
            || className.startsWith("jdk/")
            || className.startsWith("sun/")
            || className.startsWith("com/sun/")
            || className.startsWith("datadog/")
            || className.startsWith("com/datadog/")) {
          return null;
        }
      } else {
        String javaClassName = Strings.getClassName(className);
        if (!allowListHelper.isAllowed(javaClassName)) {
          return null;
        }
        if (javaClassName.startsWith("com.datadog.debugger.symbol.")) {
          // Don't parse our own classes to avoid duplicate class definition
          return null;
        }
      }
      symbolAggregator.parseClass(className, classfileBuffer, protectionDomain);
      return null;
    } catch (Exception ex) {
      LOGGER.debug("Error during extraction: ", ex);
      return null;
    }
  }

  AllowListHelper getAllowListHelper() {
    return allowListHelper;
  }
}
