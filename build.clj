(ns build
  (:require
   [clojure.tools.build.api :as b])
  (:import
   (java.time
    LocalDate)))

(def class-dir "target/classes")
(def basis (b/create-basis))
(def uber-file "target/clojars-web-standalone.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uberjar [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (println "Compiling...")
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (printf "Building uberjar %s...\n" uber-file)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'clojars.main
           ;; xml-apis ships a `license/` directory that collides with a
           ;; top-level `LICENSE` file from another lib on case-insensitive
           ;; filesystems (e.g. default macOS).
           :exclude ["^license/.*"]}))

(defn tag-release [_]
  (let [version (format "%s.%s" (LocalDate/now) (b/git-count-revs nil))]
    (b/git-process {:git-args (format "tag %s" version)})
    (b/git-process {:git-args (format "push origin %s" version)})))
