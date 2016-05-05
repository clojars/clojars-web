--
-- The schema that is actually in use in production (clojars.sql + all
-- migrations), for reference
--

CREATE TABLE users
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        user TEXT UNIQUE NOT NULL,
        password TEXT NOT NULL,
        email TEXT NOT NULL,
        ssh_key TEXT NOT NULL,
        created DATE NOT NULL,
        salt TEXT,
        pgp_key TEXT,
        password_reset_code TEXT,
        password_reset_code_created_at DATE);

CREATE TABLE jars
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        user TEXT NOT NULL,
        created DATE NOT NULL,
        description TEXT,
        homepage TEXT,
        scm TEXT,
        authors TEXT,
        promoted_at DATE,
        licenses TEXT,
        packaging TEXT);
        
CREATE INDEX jars_idx0 on jars (group_name, jar_name, created desc);

CREATE TABLE deps
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        group_name TEXT NOT NULL,
        jar_name TEXT NOT NULL,
        version TEXT NOT NULL,
        dep_group_name TEXT NOT NULL,
        dep_jar_name TEXT NOT NULL,
        dep_version TEXT NOT NULL,
        dep_scope TEXT);

CREATE TABLE groups
       (id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        user TEXT NOT NULL,
        added_by TEXT);

CREATE TABLE migrations
       (name varchar NOT NULL,
        created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP);
