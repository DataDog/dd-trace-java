package datadog.smoketest.springboot;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller()
@RequestMapping("/xss")
public class XssController {

  private static final String DIRECTORY_TEMPLATES_TEST = "resources/main/templates";
  private static final String DIRECTORY_TEMPLATES_RUN =
      "dd-smoke-tests/springboot-freemarker/src/main/resources/templates";

  @GetMapping(value = "/freemarker")
  public void freemarker(
      @RequestParam(name = "name") String name,
      @RequestParam(name = "templateName") String templateName,
      final HttpServletResponse response)
      throws IOException, TemplateException {
    Configuration cfg = new Configuration();
    cfg.setDirectoryForTemplateLoading(new File(DIRECTORY_TEMPLATES_TEST));
    Template template = cfg.getTemplate(templateName);
    Map<String, String> root = new HashMap<>();
    root.put("name", name);
    template.process(root, response.getWriter());
  }
}
