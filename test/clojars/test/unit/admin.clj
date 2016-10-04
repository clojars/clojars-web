(ns clojars.test.unit.admin
  (:require
   [clojars.admin :refer :all]
   [clojars.config :refer [config]]
   [clojars.db :as db]
   [clojars.search :as search]
   [clojars.storage :as storage]
   [clojars.test.test-helper :as help]
   [clojure.test :refer :all]
   [clojure.java.io :as io]))

(use-fixtures :each
  (join-fixtures
    [help/default-fixture
     help/with-clean-database]))

(deftest segments->path-should-work
  (are [exp given] (= exp (segments->path given))
    "a/b" ["a" "b"]
    "a/b/c" ["a.b" "c"]
    "a/b/c" ["a.b.c"]
    "a/b/c" ["a.b" nil "c" nil]))

(deftest backup-dir-should-work
  (with-redefs [current-date-str (constantly "20160827")]
    (is (= "/tmp/a-b-c-20160827" (.getAbsolutePath (backup-dir "/tmp" "a/b/c"))))))

(defn backup-exists? [path sub-path]
  (.exists (io/file (backup-dir (:deletion-backup-dir config) path) path sub-path)))

(defn in-backup-somewhere? [name]
  (some #{name} (map (memfn getName) (file-seq (io/file (:deletion-backup-dir config))))))

(def ^:dynamic *search-removals*)

(defmacro with-repo-setup [& body]
  `(let [jar# (io/file (io/resource "fake.jar"))]
     (binding [*db* help/*db*
               *search-removals* (atom #{})
               *search* (reify search/Search
                          (delete! [_ group#]
                            (swap! *search-removals* conj group#))
                          (delete! [_ group# artifact#]
                            (swap! *search-removals* conj (format "%s/%s" group# artifact#))))
               *storage* (storage/fs-storage (:repo config))]
       (db/add-jar *db* "testuser" {:group "org.ham" :name "biscuit" :version "1" :description "delete me"})
       (db/add-jar *db* "testuser" {:group "org.ham" :name "biscuit" :version "2" :description ""})
       (db/add-jar *db* "testuser" {:group "org.ham" :name "sandwich" :version "1" :description ""})
       (storage/write-artifact *storage* "org/ham/biscuit/1/biscuit-1.jar" jar#)
       (storage/write-artifact *storage* "org/ham/biscuit/1/biscuit-1.pom" jar#)
       (storage/write-artifact *storage* "org/ham/biscuit/2/biscuit-2.jar" jar#)
       (storage/write-artifact *storage* "org/ham/biscuit/2/biscuit-2.pom" jar#)
       (storage/write-artifact *storage* "org/ham/sandwich/1/sandwich-1.jar" jar#)
       (storage/write-artifact *storage* "org/ham/sandwich/1/sandwich-1.pom" jar#)

       (with-redefs [current-date-str (constantly "20160827")]
         ~@body))))

(deftest delete-group-should-work
  (with-repo-setup
    (with-out-str
      ((delete-group "org.ham")))

    (is (backup-exists? "org/ham" "biscuit/1/biscuit-1.jar"))
    (is (backup-exists? "org/ham" "biscuit/1/biscuit-1.pom"))
    (is (backup-exists? "org/ham" "biscuit/2/biscuit-2.jar"))
    (is (backup-exists? "org/ham" "biscuit/2/biscuit-2.pom"))
    (is (backup-exists? "org/ham" "sandwich/1/sandwich-1.jar"))
    (is (backup-exists? "org/ham" "sandwich/1/sandwich-1.pom"))

    (is (not (.exists (io/file (:repo config) "org/ham"))))

    (is (not (db/find-jar *db* "org.ham" "biscuit")))
    (is (not (db/find-jar *db* "org.ham" "sandwich")))
    (is (empty? (db/group-membernames *db* "org.ham")))
    (is (= #{"org.ham"} @*search-removals*))))

(deftest delete-jar-without-version-should-work
  (with-repo-setup
    (with-out-str
      ((delete-jars "org.ham" "biscuit")))

    (is (backup-exists? "org/ham/biscuit" "1/biscuit-1.jar"))
    (is (backup-exists? "org/ham/biscuit" "1/biscuit-1.pom"))
    (is (backup-exists? "org/ham/biscuit" "2/biscuit-2.jar"))
    (is (backup-exists? "org/ham/biscuit" "2/biscuit-2.pom"))
    (is (not (in-backup-somewhere? "sandwich-1.jar")))
    (is (not (in-backup-somewhere? "sandwich-1.pom")))

    (is  (not (.exists (io/file (:repo config) "org/ham/biscuit/1/biscuit-1.jar"))))
    (is  (not (.exists (io/file (:repo config) "org/ham/biscuit/1/biscuit-1.pom"))))
    (is  (not (.exists (io/file (:repo config) "org/ham/biscuit/2/biscuit-2.jar"))))
    (is  (not (.exists (io/file (:repo config) "org/ham/biscuit/2/biscuit-2.pom"))))
    (is  (.exists (io/file (:repo config) "org/ham/sandwich/1/sandwich-1.jar")))
    (is  (.exists (io/file (:repo config) "org/ham/sandwich/1/sandwich-1.pom")))

    (is (not (db/find-jar *db* "org.ham" "biscuit")))
    (is (db/find-jar *db* "org.ham" "sandwich"))
    (is (seq (db/group-membernames *db* "org.ham")))
    (is (= #{"org.ham/biscuit"} @*search-removals*))))

(deftest delete-jar-with-version-should-work
  (with-repo-setup
    (with-out-str
      ((delete-jars "org.ham" "biscuit" "1")))

    (is (backup-exists? "org/ham/biscuit/1" "biscuit-1.jar"))
    (is (backup-exists? "org/ham/biscuit/1" "biscuit-1.pom"))
    (is (not (in-backup-somewhere? "biscuit-2.jar")))
    (is (not (in-backup-somewhere? "biscuit-2.pom")))
    (is (not (in-backup-somewhere? "sandwich-1.jar")))
    (is (not (in-backup-somewhere? "sandwich-1.pom")))

    (is  (not (.exists (io/file (:repo config) "org/ham/biscuit/1/biscuit-1.jar"))))
    (is  (not (.exists (io/file (:repo config) "org/ham/biscuit/1/biscuit-1.pom"))))
    (is  (.exists (io/file (:repo config) "org/ham/biscuit/2/biscuit-2.jar")))
    (is  (.exists (io/file (:repo config) "org/ham/biscuit/2/biscuit-2.pom")))
    (is  (.exists (io/file (:repo config) "org/ham/sandwich/1/sandwich-1.jar")))
    (is  (.exists (io/file (:repo config) "org/ham/sandwich/1/sandwich-1.pom")))
    
    (is (not (db/find-jar *db* "org.ham" "biscuit" "1")))
    (is (db/find-jar *db* "org.ham" "biscuit" "2"))
    (is (db/find-jar *db* "org.ham" "sandwich"))
    (is (seq (db/group-membernames *db* "org.ham")))
    (is (empty? @*search-removals*))))
