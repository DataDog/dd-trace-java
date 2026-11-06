package datadog.trace.instrumentation.logback;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.core.pattern.PatternLayoutBase;
import ch.qos.logback.core.pattern.parser.CompositeNode;
import ch.qos.logback.core.pattern.parser.Node;
import ch.qos.logback.core.pattern.parser.Parser;
import ch.qos.logback.core.pattern.parser.SimpleKeywordNode;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.log.LogPatternInjector;
import java.util.ArrayDeque;
import java.util.List;
import net.bytebuddy.asm.Advice;

/** Adds missing correlation converters while preserving the application's pattern. */
public class PatternLayoutStartAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(@Advice.This PatternLayoutBase<?> layout) {
    if (!Config.get().isLogPatternInject() || !(layout instanceof PatternLayout)) {
      return;
    }
    String pattern = layout.getPattern();
    if (pattern == null || pattern.isEmpty()) {
      return;
    }

    int presentFields = 0;
    ArrayDeque<Node> pending = new ArrayDeque<>();
    Node root;
    try {
      root = new Parser<>(pattern).parse();
    } catch (Exception invalidPattern) {
      // Let Logback report malformed patterns through its normal validation.
      return;
    }
    if (root != null) {
      pending.push(root);
    }
    // Use the native parser to distinguish nested converters from literal option text.
    scan:
    while (!pending.isEmpty()) {
      for (Node node = pending.pop(); node != null; node = node.getNext()) {
        if (node instanceof CompositeNode) {
          Node child = ((CompositeNode) node).getChildNode();
          if (child != null) {
            pending.push(child);
          }
        } else if (node instanceof SimpleKeywordNode
            && ("X".equals(node.getValue()) || "mdc".equals(node.getValue()))) {
          List<String> options = ((SimpleKeywordNode) node).getOptions();
          if (options == null || options.isEmpty()) {
            presentFields = LogPatternInjector.ALL;
            break scan;
          }
          String key = options.get(0);
          int defaultSeparator = key.indexOf(":-");
          if (defaultSeparator >= 0) {
            key = key.substring(0, defaultSeparator);
          }
          if ("dd.trace_id".equals(key)) {
            presentFields |= LogPatternInjector.TRACE_ID;
          } else if ("dd.service".equals(key)) {
            presentFields |= LogPatternInjector.SERVICE;
          }
          if ("dd.span_id".equals(key)) {
            presentFields |= LogPatternInjector.SPAN_ID;
          }
          if (presentFields == LogPatternInjector.ALL) {
            break scan;
          }
        }
      }
    }
    if (presentFields != LogPatternInjector.ALL) {
      layout.setPattern(LogPatternInjector.injectLogback(pattern, presentFields));
    }
  }
}
