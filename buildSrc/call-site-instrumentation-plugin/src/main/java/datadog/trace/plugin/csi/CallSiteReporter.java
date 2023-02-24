package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface CallSiteReporter {

  void report(List<CallSiteResult> results, boolean error);

  static CallSiteReporter getReporter(final String type) {
    if ("CONSOLE".equals(type)) {
      return new ConsoleReporter();
    }
    throw new IllegalArgumentException("Reporter of type '" + type + "' not supported");
  }

  abstract class FreemarkerReporter implements CallSiteReporter {
    private final String templateName;

    protected FreemarkerReporter(final String templateName) {
      this.templateName = templateName;
    }

    protected void write(final List<CallSiteResult> results, final Writer writer) {
      try {
        final Configuration cfg = new Configuration(Configuration.VERSION_2_3_30);
        cfg.setClassLoaderForTemplateLoading(Thread.currentThread().getContextClassLoader(), "csi");
        cfg.setDefaultEncoding("UTF-8");
        final Map<String, Object> input = new HashMap<>();
        input.put("results", results);
        final Template template = cfg.getTemplate(templateName);
        template.process(input, writer);
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  class ConsoleReporter extends FreemarkerReporter {

    protected ConsoleReporter() {
      super("console.ftl");
    }

    @Override
    public void report(final List<CallSiteResult> results, final boolean error) {
      final PrintStream stream = error ? System.err : System.out;
      final StringWriter writer = new StringWriter();
      write(results, writer);
      stream.println(writer);
    }
  }
}
