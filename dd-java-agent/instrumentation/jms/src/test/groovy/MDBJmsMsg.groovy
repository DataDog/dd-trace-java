import javax.jms.Message
import javax.jms.Destination
import javax.jms.JMSException

class MDBJmsMsg implements Message {

  long getJMSDeliveryTime() {
    return 1L
  }

  void setJMSDeliveryTime(long time) {}

  Object getBody(Class aClass) {
    return null
  }

  boolean isBodyAssignableTo(Class aClass) {
    return false
  }

  @Override
  String getJMSMessageID() throws JMSException {
    return "123"
  }

  @Override
  void setJMSMessageID(String id) throws JMSException {}

  @Override
  long getJMSTimestamp() throws JMSException {
    return 0
  }

  @Override
  void setJMSTimestamp(long timestamp) throws JMSException {
  }

  @Override
  byte[] getJMSCorrelationIDAsBytes() throws JMSException {
    return new byte[0]
  }

  @Override
  void setJMSCorrelationIDAsBytes(byte[] correlationID) throws JMSException {
  }

  @Override
  void setJMSCorrelationID(String correlationID) throws JMSException {
  }

  @Override
  String getJMSCorrelationID() throws JMSException {
    return null
  }

  @Override
  Destination getJMSReplyTo() throws JMSException {
    return null
  }

  @Override
  void setJMSReplyTo(Destination replyTo) throws JMSException {
  }

  @Override
  Destination getJMSDestination() throws JMSException {
    return null
  }

  @Override
  void setJMSDestination(Destination destination) throws JMSException {
  }

  @Override
  int getJMSDeliveryMode() throws JMSException {
    return 0
  }

  @Override
  void setJMSDeliveryMode(int deliveryMode) throws JMSException {
  }

  @Override
  boolean getJMSRedelivered() throws JMSException {
    return false
  }

  @Override
  void setJMSRedelivered(boolean redelivered) throws JMSException {
  }

  @Override
  String getJMSType() throws JMSException {
    return null
  }

  @Override
  void setJMSType(String type) throws JMSException {
  }

  @Override
  long getJMSExpiration() throws JMSException {
    return 0
  }

  @Override
  void setJMSExpiration(long expiration) throws JMSException {
  }

  @Override
  int getJMSPriority() throws JMSException {
    return 0
  }

  @Override
  void setJMSPriority(int priority) throws JMSException {
  }

  @Override
  void clearProperties() throws JMSException {
  }

  @Override
  boolean propertyExists(String name) throws JMSException {
    return false
  }

  @Override
  boolean getBooleanProperty(String name) throws JMSException {
    return false
  }

  @Override
  byte getByteProperty(String name) throws JMSException {
    return 0
  }

  @Override
  short getShortProperty(String name) throws JMSException {
    return 0
  }

  @Override
  int getIntProperty(String name) throws JMSException {
    return 0
  }

  @Override
  long getLongProperty(String name) throws JMSException {
    return 0
  }

  @Override
  float getFloatProperty(String name) throws JMSException {
    return 0
  }

  @Override
  double getDoubleProperty(String name) throws JMSException {
    return 0
  }

  @Override
  String getStringProperty(String name) throws JMSException {
    return null
  }

  @Override
  Object getObjectProperty(String name) throws JMSException {
    return null
  }

  @Override
  Enumeration getPropertyNames() throws JMSException {
    return null
  }

  @Override
  void setBooleanProperty(String name, boolean value) throws JMSException {
  }

  @Override
  void setByteProperty(String name, byte value) throws JMSException {
  }

  @Override
  void setShortProperty(String name, short value) throws JMSException {
  }

  @Override
  void setIntProperty(String name, int value) throws JMSException {
  }

  @Override
  void setLongProperty(String name, long value) throws JMSException {
  }

  @Override
  void setFloatProperty(String name, float value) throws JMSException {
  }

  @Override
  void setDoubleProperty(String name, double value) throws JMSException {
  }

  @Override
  void setStringProperty(String name, String value) throws JMSException {
  }

  @Override
  void setObjectProperty(String name, Object value) throws JMSException {
  }

  @Override
  void acknowledge() throws JMSException {
  }

  @Override
  void clearBody() throws JMSException {
  }
}



