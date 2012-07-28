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
   select id, jar_name, group_name,
          jar_name || ' ' || 
          group_name || ' ' || 
          version || ' ' || 
          coalesce(authors, '') || ' ' || 
          user || ' ' ||
          coalesce(description, '')
   from jars
   group by group_name, jar_name;
commit;
