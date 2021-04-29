package app

import datadog.trace.agent.test.base.HttpServerTest
import org.apache.struts.action.Action
import org.apache.struts.action.ActionForm
import org.apache.struts.action.ActionForward
import org.apache.struts.action.ActionMapping

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION

class CustomExceptionAction extends Action {

  @Override
  ActionForward execute(
    ActionMapping mapping,
    ActionForm form,
    HttpServletRequest request,
    HttpServletResponse response) {
    HttpServerTest.controller(CUSTOM_EXCEPTION) {
      throw new InputMismatchException(CUSTOM_EXCEPTION.body)
    }
  }
}
