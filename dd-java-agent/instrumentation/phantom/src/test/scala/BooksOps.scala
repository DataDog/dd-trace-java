import java.util.UUID

import com.outworkers.phantom.dsl._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}

class BooksOps(database: BooksDatabase) {
  implicit val session = database.session
  implicit val space = database.space

  def insertBook(id: UUID, ecc: ExecutionContextExecutor): Unit = {
    val testBook = Book(id, "Programming in Scala", "Odersky")
    val insertFuture = database.Books.update()
      .where(_.id eqs testBook.id)
      .modify(_.title setTo testBook.title)
      .and(_.author setTo testBook.author)
      .future()(session, ecc)

    Await.result(insertFuture, 5.seconds)
  }


}
