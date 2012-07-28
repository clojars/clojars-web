--
-- Rebuild the SQLite search index
--
-- Use this if the search segments get corrupted and you start seeing 
-- errors like these when searching:
--
--    SQL error or missing database (SQL logic error or missing database)
--    Error: database disk image is malformed 
--

begin;
delete from search;
insert into search (id, jar_name, group_name, content) 
   select new.id, new.jar_name, new.group_name,
          new.jar_name || ' ' || 
          new.group_name || ' ' || 
          new.version || ' ' || 
          coalesce(new.authors, '') || ' ' || 
          new.user || ' ' ||
          coalesce(new.description, '')
   from jars new;
commit;
