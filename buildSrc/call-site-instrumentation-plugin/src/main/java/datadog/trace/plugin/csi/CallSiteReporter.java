package datadog.trace.plugin.csi;

import datadog.trace.plugin.csi.AdviceGenerator.CallSiteResult;
import freemarker.ext.beans.StringModel;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface CallSiteReporter {

  void report(List<CallSiteResult> results, boolean error);

  static CallSiteReporter getReporter(final String type) {
    if ("CONSOLE".equals(type)) {
      return new ConsoleReporter();
    }
    throw new IllegalArgumentException("Reporter of type '" + type + "' not supported");
  }

  abstract class FreemarkerReporter implements CallSiteReporter {
    private final String template;

    protected FreemarkerReporter(final String template) {
      this.template = template;
    }

    protected void write(final List<CallSiteResult> results, final Writer writer) {
      try {
        final Configuration cfg = new Configuration(Configuration.VERSION_2_3_30);
        cfg.setClassLoaderForTemplateLoading(Thread.currentThread().getContextClassLoader(), "csi");
        cfg.setDefaultEncoding("UTF-8");
        final Map<String, Object> input = new HashMap<>();
        input.put("results", results);
        input.put("toList", new ToListDirective());
        final Template template = cfg.getTemplate("console.ftl");
        template.process(input, writer);
      } catch (final Exception e) {
        throw new RuntimeException(e);
      }
    }

    private static class ToListDirective implements TemplateMethodModelEx {

      @Override
      public Object exec(final List arguments) throws TemplateModelException {
        final StringModel model = (StringModel) arguments.get(0);
        final Stream<?> stream = (Stream<?>) model.getWrappedObject();
        return stream.collect(Collectors.toList());
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
