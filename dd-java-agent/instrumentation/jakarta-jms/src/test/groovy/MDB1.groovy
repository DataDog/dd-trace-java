import jakarta.ejb.MessageDrivenBean
import jakarta.ejb.MessageDrivenContext
import jakarta.jms.Message
import jakarta.jms.MessageListener

class MDB1 implements MessageDrivenBean, MessageListener {

  void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }
  void ejbRemove() {}
  void setMessageDrivenContext(MessageDrivenContext ctx) {}
}

