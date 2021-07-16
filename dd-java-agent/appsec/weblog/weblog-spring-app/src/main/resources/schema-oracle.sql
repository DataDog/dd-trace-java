-- catch the 'table not found' when dropping table
-- because oracle have no direct equivalent of 'drop if exists' like mysql
--
-- https://stackoverflow.com/questions/1799128/oracle-if-table-exists)
BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE thetable';
EXCEPTION
   WHEN OTHERS THEN
      IF SQLCODE != -942 THEN
         RAISE;
      END IF;
END;^;
-- the extra ';' is required after END otherwise won't compile

----
CREATE TABLE thetable(
    id int,
    val varchar(512) not null,
    primary key(id)
)^;

-- Could be created by hibernate, but we insert data afterwards

BEGIN
   EXECUTE IMMEDIATE 'DROP TABLE posts';
EXCEPTION
   WHEN OTHERS THEN
      IF SQLCODE != -942 THEN
         RAISE;
      END IF;
END;^;
-- the extra ';' is required after END otherwise won't compile

CREATE TABLE posts(
     id int,
     author varchar(256) not null,
     title varchar(512) not null,
     body varchar(2000) not null, -- limited to 2k on oracle
     created_at timestamp with time zone not null,
     primary key(id)
)^;

INSERT INTO posts(id, author, title, body, created_at) VALUES (1, 'Sample author', 'sample title', 'my sample body', CURRENT_TIMESTAMP)^;
INSERT INTO thetable(id, val) VALUES(1, 'One value')^;
INSERT INTO thetable(id, val) VALUES(2, 'Another value')^;

-- commit required because no auto-commit by default
COMMIT^;