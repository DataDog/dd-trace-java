package com.datadog.debugger.symbol;

import com.datadog.debugger.agent.AllowListHelper;
import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.sink.SymbolSink;
import datadog.trace.api.Config;
import datadog.trace.util.Strings;
import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolExtractionTransformer implements ClassFileTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolExtractionTransformer.class);
  private static final Pattern COMMA_PATTERN = Pattern.compile(",");

  private final SymbolSink sink;
  private final Map<String, Scope> jarScopesByName = new HashMap<>();
  private final Object jarScopeLock = new Object();
  private final AllowListHelper allowListHelper;
  private int totalClasses;
  private final int symbolFlushThreshold;

  public SymbolExtractionTransformer() {
    this(new SymbolSink(Config.get()), Config.get());
  }

  public SymbolExtractionTransformer(SymbolSink sink, Config config) {
    this.sink = sink;
    String includes = config.getDebuggerSymbolIncludes();
    if (includes != null) {
      this.allowListHelper = new AllowListHelper(buildFilterList(includes));
    } else {
      this.allowListHelper = null;
    }
    this.symbolFlushThreshold = config.getDebuggerSymbolFlushThreshold();
  }

  private Configuration.FilterList buildFilterList(String includes) {
    String[] includeParts = COMMA_PATTERN.split(includes);
    return new Configuration.FilterList(Arrays.asList(includeParts), Collections.emptyList());
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
    if (allowListHelper == null) {
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
      if (!allowListHelper.isAllowed(Strings.getClassName(className))) {
        return null;
      }
    }
    String jarName = "DEFAULT";
    if (protectionDomain != null) {
      CodeSource codeSource = protectionDomain.getCodeSource();
      if (codeSource != null) {
        URL location = codeSource.getLocation();
        if (location != null) {
          jarName = location.getFile();
        }
      }
    }
    LOGGER.debug("Extracting Symbols from: {}, located in: {}", className, jarName);
    Scope jarScope = SymbolExtractor.extract(classfileBuffer, jarName);
    addJarScope(jarScope);
    return null;
  }

  private void addJarScope(Scope jarScope) {
    List<Scope> scopes = Collections.emptyList();
    synchronized (jarScopeLock) {
      Scope scope = jarScopesByName.get(jarScope.getName());
      if (scope != null) {
        scope.getScopes().addAll(jarScope.getScopes());
      } else {
        jarScopesByName.put(jarScope.getName(), jarScope);
      }
      totalClasses++;
      if (totalClasses >= symbolFlushThreshold) {
        scopes = new ArrayList<>(jarScopesByName.values());
        jarScopesByName.clear();
        totalClasses = 0;
      }
    }
    if (!scopes.isEmpty()) {
      LOGGER.debug("dumping {} jar scopes to sink", scopes.size());
      for (Scope scope : scopes) {
        LOGGER.debug(
            "dumping {} class scopes to sink from scope: {}",
            scope.getScopes().size(),
            scope.getName());
        sink.addScope(scope);
      }
    }
  }
}
