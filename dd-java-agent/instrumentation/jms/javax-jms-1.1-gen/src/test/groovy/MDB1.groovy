import javax.ejb.MessageDrivenBean
import javax.ejb.MessageDrivenContext
import javax.jms.Message
import javax.jms.MessageListener

class MDB1 implements MessageDrivenBean, MessageListener {

  void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }
  void ejbRemove() {}
  void setMessageDrivenContext(MessageDrivenContext ctx) {}
}

