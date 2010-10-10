create table users
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        user TEXT UNIQUE NOT NULL,
        password TEXT NOT NULL,
        salt TEXT NOT NULL,
        email TEXT NOT NULL,
        ssh_key TEXT NOT NULL,
        created DATE NOT NULL);

create table jars
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        user TEXT NOT NULL,
        created DATE NOT NULL,
        description TEXT,
        homepage TEXT,
        scm TEXT,
        authors TEXT);
        
create table deps
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        dep_group_name TEXT NOT NULL,
        dep_jar_name TEXT NOT NULL,
        dep_version TEXT NOT NULL);

create table groups
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        user TEXT NOT NULL);

--
-- Search support: quick and dirty, but it works
--
        
create virtual table search using fts3
       (id INTEGER PRIMARY KEY,
       content text not null,
       jar_name text not null,
       group_name text not null);

create trigger insert_search insert on jars
  begin
    delete from search where jar_name = new.jar_name and group_name = new.group_name;
    insert into search (id, jar_name, group_name, content) values
           (new.id, new.jar_name, new.group_name,
           new.jar_name || ' ' || 
           new.group_name || ' ' || 
           new.version || ' ' || 
           coalesce(new.authors, '') || ' ' || 
           new.user || ' ' ||
           coalesce(new.description, ''));
  end;

create trigger update_search update on jars
  begin
    delete from search where jar_name = new.jar_name and group_name = new.group_name;
    insert into search (id, jar_name, group_name, content) values
           (new.id, new.jar_name, new.group_name,
           new.jar_name || ' ' || 
           new.group_name || ' ' || 
           new.version || ' ' || 
           coalesce(new.authors, '') || ' ' || 
           new.user || ' ' ||
           coalesce(new.description, ''));
  end;


