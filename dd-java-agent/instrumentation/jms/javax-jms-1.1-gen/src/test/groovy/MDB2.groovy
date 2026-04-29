import javax.ejb.MessageDriven
import javax.jms.Message
import javax.jms.MessageListener

@MessageDriven(mappedName="unusedTopic")
class MDB2 implements MessageListener {

  void onMessage(Message message) {
    if (message == null) {
      throw new Exception("null message")
    }
  }
}

