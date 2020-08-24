package test.dynamic

import org.springframework.context.ApplicationContext
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.handler.AbstractHandlerMapping

import javax.servlet.http.HttpServletRequest
import java.lang.reflect.Method

class DynamicHandlerMapping extends AbstractHandlerMapping {

  @Override
  protected Object getHandlerInternal(HttpServletRequest request) throws Exception {
    ApplicationContext applicationContext = this.getApplicationContext()
    DynamicRoutingConfig routingConfig = applicationContext
      .getBean(DynamicRoutingConfig)
    Class<?> controllerClass = Class.forName(routingConfig.getController())
    Object controller = applicationContext.getBean(controllerClass)
    String methodName = request.getServletPath().replace('-', '_')
    methodName = methodName.substring(1)
    int nextSlash = methodName.indexOf('/')
    if (nextSlash != -1) {
      methodName = methodName.substring(0, nextSlash)
    }
    if (methodName == "error_status") {
      // easier than refactoring the entire test hierarchy to
      // reflect this particular use case
      methodName = "error"
    }
    Method method = null
    Method[] methods = controllerClass.getDeclaredMethods()
    for (Method m : methods) {
      if (m.getName() == methodName) {
        method = m
        break
      }
    }
    return new HandlerMethod(controller, method)
  }
}
