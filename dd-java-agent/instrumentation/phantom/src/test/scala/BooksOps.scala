// This code is in it's own compilation unit to keep the scope of DefaultImports.context contained
import com.outworkers.phantom.dsl._
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

class BooksOps(database: BooksDatabase) {
  implicit val session = database.session
  implicit val space = database.space

  def insertBook(book: Book, ecc: ExecutionContextExecutor): Future[ResultSet] = {
    database.Books.update()
      .where(_.id eqs book.id)
      .modify(_.title setTo book.title)
      .and(_.author setTo book.author)
      .future()(session, ecc)
  }

  def setBookStatus(id: UUID, status: String, ecc: ExecutionContextExecutor): Future[ResultSet] = {
    database.Books.update()
      .where(_.id eqs id)
      .modify(_.status setTo status)
      .future()(session, ecc)
  }

  def setInventory(id: UUID, count: Int, ecc: ExecutionContextExecutor): Future[ResultSet] = {
    database.Books.update()
      .where(_.id eqs id)
      .modify(_.inventory setTo count)
      .future()(session, ecc)
  }
}
