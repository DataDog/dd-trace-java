package io.sqreen.testapp.thymeleaf3

import org.springframework.web.servlet.View
import org.thymeleaf.ITemplateEngine
import org.thymeleaf.context.Context
import org.thymeleaf.context.IContext

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class Thymeleaf3View implements View {
  ITemplateEngine templateEngine
  Locale locale
  String viewName

  @Override
  String getContentType() {
    'text/html;charset=utf-8'
  }

  @Override
  void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response) throws Exception {
    IContext ctx = new Context(locale, model)
    response.contentType = contentType
    Writer w = response.writer
    templateEngine.process(viewName, ctx, w)
    w.close()
  }
}
