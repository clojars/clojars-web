create table users
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        user TEXT UNIQUE NOT NULL,
        password TEXT NOT NULL,
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
        
