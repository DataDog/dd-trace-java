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

  @GetMapping(value = "/freemarker")
  public void freemarker(
      @RequestParam(name = "name") String name, final HttpServletResponse response)
      throws IOException, TemplateException {
    Configuration cfg = new Configuration(Configuration.VERSION_2_3_23);
    cfg.setDirectoryForTemplateLoading(
        new File("dd-smoke-tests/springboot-freemarker/src/main/resources/templates"));
    Template template = cfg.getTemplate("freemarker.ftlh");
    Map<String, String> root = new HashMap<>();
    root.put("name", name);
    //    template.process(
    //        root,
    //        new FileWriter(
    //            "dd-smoke-tests/springboot-freemarker/src/main/resources/templates/output.txt"));
    template.process(root, response.getWriter());
  }
}
