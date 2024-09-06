package datadog.smoketest.springboot.controller;

import datadog.smoketest.springboot.domain.Content;
import datadog.smoketest.springboot.domain.query.QContent;
import io.ebean.Database;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/content")
public class ContentController {

  @Autowired Database database;

  @PutMapping("/ebeanPut")
  public void update(@RequestBody() Content body) {
    Content content = new Content("previousName");
    database.save(content);

    Content dbContent;
    dbContent = new QContent().id.eq(body.getId()).findOne();

    dbContent.setName(body.getName());
    dbContent.save();
  }

  @PutMapping("/ebeanName")
  public void update(@RequestParam("name") String name) {
    Content content = new Content("previousName");
    database.save(content);

    Content dbContent;
    dbContent = new QContent().id.eq(1).findOne();

    dbContent.setName(name);
    dbContent.save();
    //    ContentUser contentUser = new ContentUser(name);
    //    database.save(contentUser);
    //
    //    Content content = new Content("previousName");
    //    content.setContentUser(contentUser);
    //    database.save(content);
    //    SqlUpdate sqlUpdate = database.sqlUpdate("Update exa_content set " + name + " = " + name +
    // " where id = 1");
    //    sqlUpdate.execute();
    //    int row = database.update(Content.class)
    //        .setRaw(name + " = 'new name' where id = 1")
    //        .setRaw("Update exa_content set " + name + " = 'new name' where id = 1")
    //        .update();

    //    database.update(Content.class)
    //        .set(name, name)
    //            .where().eq("id", id)
    //        .update();

    //    int rows = new QContent();
    //    int rows = new QCustomer()
    //        .name.startsWith("Rob")
    //        .asUpdate()                      // convert to UpdateQuery
    //        .set("registered", now)        // update set ...
    //        .update();

    //    String sql = "update content set " + name + " = ? where id = ?";
    //
    //    int row = database.sqlUpdate(sql)
    //        .setParameter(1, "name")
    //        .setParameter(2, id)
    //        .execute();

    //      content.setName(name);
    //    contentUser.setContentUserName(name);
    //    content.setContentUser(contentUser);
    //      MergeOptions options = new MergeOptionsBuilder()
    //          .addPath(name)
    //          .build();
    //      database.merge(content1, options);
    //    database.merge(content);
  }
}
