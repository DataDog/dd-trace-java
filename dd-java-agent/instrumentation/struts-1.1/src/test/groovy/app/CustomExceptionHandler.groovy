package app

import org.apache.struts.action.ActionForm
import org.apache.struts.action.ActionForward
import org.apache.struts.action.ActionMapping
import org.apache.struts.action.ExceptionHandler
import org.apache.struts.config.ExceptionConfig

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class CustomExceptionHandler extends ExceptionHandler {
  @Override
  ActionForward execute(Exception ex, ExceptionConfig ae, ActionMapping mapping, ActionForm formInstance, HttpServletRequest request, HttpServletResponse response) throws ServletException {
    return super.execute(ex, ae, mapping, formInstance, request, response)
  }
}
