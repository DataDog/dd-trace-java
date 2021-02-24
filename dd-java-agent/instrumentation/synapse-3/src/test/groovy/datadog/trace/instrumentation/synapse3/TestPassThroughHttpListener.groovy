package datadog.trace.instrumentation.synapse3

import datadog.trace.agent.test.utils.PortUtils
import org.apache.axis2.AxisFault
import org.apache.axis2.context.ConfigurationContext
import org.apache.axis2.description.Parameter
import org.apache.axis2.description.TransportInDescription
import org.apache.synapse.transport.passthru.PassThroughHttpListener

class TestPassThroughHttpListener extends PassThroughHttpListener {
  public static final int PORT = PortUtils.randomOpenPort()

  @Override
  void init(ConfigurationContext axisConf, TransportInDescription transportIn) throws AxisFault {
    Parameter param = transportIn.getParameter(PARAM_PORT)
    if (param != null) {
      param.setValue(Integer.toString(PORT))
    }
    super.init(axisConf, transportIn)
  }
}
