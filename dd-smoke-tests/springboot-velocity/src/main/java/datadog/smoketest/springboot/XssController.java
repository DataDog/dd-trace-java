package datadog.smoketest.springboot;

import javax.servlet.http.HttpServletResponse;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.tools.generic.EscapeTool;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/xss")
public class XssController {

  @GetMapping("/velocity")
  public void xssVelocity(@RequestParam("velocity") String param, HttpServletResponse response)
      throws Exception {
    VelocityEngine velocity = new VelocityEngine();
    // To avoid the creation of a Velocity log file
    velocity.setProperty(
        RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
        "org.apache.velocity.runtime.log.NullLogChute");
    velocity.init();
    Template template =
        velocity.getTemplate(
            "dd-smoke-tests/springboot-velocity/src/main/resources/templates/velocity.vm");

    //    Map<String, Object> properties = new HashMap<String, Object>();
    //    properties.put("engine", velocity);
    //    ToolManager manager = new ToolManager(autoConfigure, includeDefaults);

    VelocityContext context = new VelocityContext();
    context.put("esc", new EscapeTool());
    context.put("param", param);

    //    StringWriter writer = new StringWriter();
    template.merge(context, response.getWriter());
    //    return "OK";
  }
}
