package datadog.smoketest.springboot;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring5.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

@Controller
@RequestMapping("/xss")
public class XssController {

  private static final String TEMPLATE = "<p th:utext=\"${xss}\">Test!</p>";

  private static final String BIG_TEMPLATE =
      new String(new char[500]).replace('\0', 'A') + "<p th:utext=\"${xss}\">Test!</p>";

  @GetMapping("/utext")
  public String utext(@RequestParam(name = "string") String name, Model model) {
    model.addAttribute("xss", name);
    return "utext";
  }

  @GetMapping(value = "/string-template", produces = "text/html")
  @ResponseBody
  public String stringTemplate(@RequestParam(name = "string") String name) {
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(new StringTemplateResolver());
    Context context = new Context();
    context.setVariable("xss", name);
    return engine.process(TEMPLATE, context);
  }

  @GetMapping(value = "/big-string-template", produces = "text/html")
  @ResponseBody
  public String bigStringTemplate(@RequestParam(name = "string") String name) {
    SpringTemplateEngine engine = new SpringTemplateEngine();
    engine.setTemplateResolver(new StringTemplateResolver());
    Context context = new Context();
    context.setVariable("xss", name);
    return engine.process(BIG_TEMPLATE, context);
  }
}
