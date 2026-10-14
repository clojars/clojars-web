(ns clojars.models.jar
  (:require
   [clojars.db :as db]
   [clojars.maven :as mvn]
   [clojars.models.group-verification :as group-verification-m]
   [clojars.models.permission :as permission-m]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [medley.core :refer [assoc-some]]
   [next.jdbc.sql :as sql])
  (:import
   (java.sql
    Timestamp)
   (java.time
    Instant)
   (java.util
    Date)))

(set! *warn-on-reflection* true)
(defn jar-exists
  [db groupname jarname]
  (->
   (db/q db
         {:select 1
          :from :jars
          :where [:and
                  [:= :group_name groupname]
                  [:= :jar_name jarname]]})
   (first)
   (some?)))

(defn check-group+project
  "Throws if the group does not exist, the account does not have permission to
  deploy the project, or the group is not verified (when the jarname doesn't
  already exist).

  We only allow deploys of new jars to verified groups. New versions of existing jars
  can still be deployed to non-verified groups."
  [db account groupname jarname]
  (let [jar-actives (permission-m/jar-active-usernames db groupname jarname)
        err (fn [error-tag]
              (throw (ex-info "Group/project error"
                              {:account   account
                               :error-tag error-tag
                               :group     groupname
                               :project   jarname})))]
    (cond
      ;; group exists, but user doesn't have rights to deploy to the given project
      (and (seq jar-actives)
           (not (contains? jar-actives account)))
      (err :group-no-access)

      ;; group/jar exists, so new versions can be deployed
      (jar-exists db groupname jarname)
      true

      ;; group doesn't exist, reject since we no longer auto-create groups
      (empty? jar-actives)
      (err :group-non-existent)

      ;; group isn't verified, so new jars/projects can't be deployed to it
      (not (group-verification-m/find-group-verification db groupname))
      (err :group-non-verified))))

(def str-map
  (s/map-of keyword? string?))

(def str-map-vector
  (s/or :empty? empty?
        :str-maps (s/coll-of str-map)))

;; public so we can override in tests
(defn safe-pr-str
  "Checks v against the given spec, throwing if it isn't valid. If it is
  valid, v is passed through pr-str. This is used to prevent XSS as
  hiccup data structures from getting into the db."
  [v spec]
  (pr-str (s/assert* spec v)))

(defn- group+jar+version-where
  [group-id jar-id version]
  (assoc-some {:group_name group-id}
              :jar_name jar-id
              :version version))

(defn- delete-dependencies
  [db group-id jar-id version]
  (sql/delete! db :deps (group+jar+version-where group-id jar-id version)))

(defn add-jar
  [db account {:keys [group name version description homepage authors packaging licenses scm dependencies]}]
  (check-group+project db account group name)
  (sql/insert! db :jars
               {:group_name  group
                :jar_name    name
                :version     version
                db/user-column  account
                :created     (db/get-time)
                :description description
                :homepage    homepage
                :packaging   (when packaging (clojure.core/name packaging))
                :licenses    (when licenses (safe-pr-str licenses str-map-vector))
                :scm         (when scm (safe-pr-str scm str-map))
                :authors     (str/join ", " (map #(.replace ^String % "," "")
                                                 authors))})
  (when (mvn/snapshot-version? version)
    (delete-dependencies db group name version))
  (doseq [dep dependencies]
    (sql/insert! db :deps
                 (-> dep
                     (set/rename-keys {:group_name :dep_group_name
                                       :jar_name   :dep_jar_name
                                       :version    :dep_version
                                       :scope      :dep_scope})
                     (assoc :group_name group
                            :jar_name   name
                            :version   version)))))

(defn- delete-jars*
  [db group-id jar-id version]
  (sql/delete! db :jars (group+jar+version-where group-id jar-id version)))

(defn delete-jars [db group-id & [jar-id version]]
  (delete-jars* db group-id jar-id version)
  (delete-dependencies db group-id jar-id version))

(defn jars-by-username
  [db username]
  (db/q db
        {:select :j/*
         :from [[:jars :j]]
         :join [[{:select [:group_name :jar_name [[:max :created] :created]]
                  :from :jars
                  :where [:= :user username]
                  :group-by [:group_name :jar_name]}
                 :l]
                [:and
                 [:= :j/group_name :l/group_name]
                 [:= :j/jar_name :l/jar_name]
                 [:= :j/created :l/created]]]
         :order-by [[:j/group_name :asc] [:j/jar_name :asc]]}))

(defn jars-by-groupname
  [db groupname]
  (db/q db
        {:select :j/*
         :from [[:jars :j]]
         :join [[{:select [:jar_name [[:max :created] :created]]
                  :from :jars
                  :where [:= :group_name groupname]
                  :group-by [:group_name :jar_name]}
                 :l]
                [:and
                 [:= :j/jar_name :l/jar_name]
                 [:= :j/created :l/created]]]
         :order-by [[:j/group_name :asc] [:j/jar_name :asc]]}))

(defn jars-by-groups-for-username
  [db username]
  (db/q db
        {:select :j/*
         :from [[:jars :j]]
         :join [[{:select [:jar_name [[:max :created] :created]]
                  :from :jars
                  :where [:in :group_name
                          {:select-distinct :group_name
                           :from :permissions
                           :where [:and
                                   [:= :user username]
                                   [:not [:is :inactive true]]]}]
                  :group-by [:group_name :jar_name]}
                 :l]
                [:and
                 [:= :j/jar_name :l/jar_name]
                 [:= :j/created :l/created]]]
         :order-by [[:j/group_name :asc] [:j/jar_name :asc]]}))

(defn recent-versions
  ([db groupname jarname]
   (recent-versions db groupname jarname nil))
  ([db groupname jarname num]
   (mapv #(select-keys % [:version])
         (db/q db
               (cond-> {:select :version
                        :from [[{:select-distinct-on [[:version] :version :created]
                                 :from :jars
                                 :where
                                 [:and
                                  [:= :group_name groupname]
                                  [:= :jar_name jarname]]}
                                :distinct_jars]]
                        :order-by [[:distinct_jars/created :desc]]}
                 num (assoc :limit num))))))


(defn count-versions
  [db groupname jarname]
  (->
   (db/q db
         {:select [[[:count [:distinct :version]] :count]]
          :from :jars
          :where [:and
                  [:= :group_name groupname]
                  [:= :jar_name jarname]]
          :limit 1})
   (first)
   :count))

(defn max-jars-id
  [db]
  (->
   (db/q db
         {:select [[[:max :id] :max_id]]
          :from :jars
          :limit 1})
   (first)
   :max_id))

(defn recent-jars
  [db]
  (db/q db
        {:select :j/*
         :from [[:jars :j]]
         :join [[{:select [:group_name :jar_name [[:max :created] :created]]
                  :from :jars
                  :group-by [:group_name :jar_name]
                  :order-by [[:created :desc]]
                  :limit 6}
                 :l]
                [:and
                 [:= :j/group_name :l/group_name]
                 [:= :j/jar_name :l/jar_name]
                 [:= :j/created :l/created]]]
         :order-by [[:l/created :desc]]
         :limit 6}))

(defn- safe-edn-read
  "Reads edn data, then assures it matches the given spec, returning nil otherwise.
  This is used to prevent XSS in case there is some avenue to get
  hiccup data structures into the :license or :scm fields in the db."
  [s spec]
  (let [m (edn/read-string s)]
    (when (s/valid? spec m)
      m)))

(let [read-edn-fields #(some-> %
                               (update :licenses safe-edn-read str-map-vector)
                               (update :scm safe-edn-read str-map))]
  (defn find-jar
    ([db groupname jarname]
     (find-jar db groupname jarname nil))
    ([db groupname jarname version]
     (-> (db/q db
               {:select :*
                :from :jars
                :where (cond-> [:and
                                [:= :group_name groupname]
                                [:= :jar_name jarname]]
                         version (conj [:= :version version]))
                :order-by (if version
                            [[:created :desc]]
                            [[[:like :version [:inline "%-SNAPSHOT"]] :asc]
                             [:created :desc]])
                :limit 1})
         (first)
         (read-edn-fields))))

  (defn all-jars [db]
    (map read-edn-fields
         (db/q db {:select   :*
                   :from     :jars
                   :order-by :id}))))

(defn- version-feed*
  [db ^Instant from-inst limit]
  (db/q db {:select   [:created :description :group_name :jar_name :version]
            :from     :jars
            ;; This uses the jars_idx_created index
            :where    [:> :created (Timestamp/from from-inst)]
            :order-by :created
            :limit    limit}))

(defn version-feed
  [db from-inst limit]
  ;; Get the jars, but with one extra
  (let [jars (version-feed* db from-inst (inc limit))]
    ;; If we got back fewer jars than we asked for, just return them, as there
    ;; aren't hitting a page boundary
    (if (<= (count jars) limit)
      jars
      ;; Drop any jars from the end of the page that were in the same
      ;; millisecond as the first jar on what would normally be the next page.
      ;; This prevents dropping a release if:
      ;; - the release occurs in the same millisecond as another release
      ;; - both of those releases are at the page boundary (one would be the
      ;;   last release on a page, where the other (the missed one) would be
      ;;   the first on the next page)
      (let [[extra-jar & jars-reversed] (reverse jars)
            extra-jar-created-ms (.getTime ^Date (:created extra-jar))
            results-reversed (drop-while #(= extra-jar-created-ms
                                             (.getTime ^Date (:created %)))
                                         jars-reversed)]
        (take limit (reverse results-reversed))))))

(defn all-projects
  [db offset-num limit-num]
  (db/q db
        {:select-distinct [:group_name :jar_name]
         :from :jars
         :order-by [[:group_name :asc] [:jar_name :asc]]
         :limit limit-num
         :offset offset-num}))

(defn count-all-projects
  [db]
  (->
   (db/q db
         {:select [[[:count :*] :count]]
          :from [[{:select-distinct [:group_name :jar_name]
                   :from :jars}
                  :sub]]})
   (first)
   :count))

(defn count-projects-before [db s]
  (->
   (db/q db
         {:select [[[:count :*] :count]]
          :from [[{:select-distinct [:group_name :jar_name]
                   :from :jars
                   :order-by [:group_name :jar_name]}
                  :sub]]
          :where [[:< [:raw "group_name || '/' || jar_name"] s]]})
   (first)
   :count))

(defn browse-projects [db current-page per-page]
  (vec
   (map
    (fn [{:keys [group_name jar_name]}]
      (find-jar db group_name jar_name))
    (all-projects db
                  (* (dec current-page) per-page)
                  per-page))))

(defn- find-groups-jars-information
  [db group-id]
  (db/q db
        {:select [:j/jar_name :j/group_name :homepage :description
                  :user [:j/version :latest_version] [:r2/version :latest_release]]
         :from [[:jars :j]]
         ;; find the latest version
         :join [[{:select [:jar_name :group_name [[:max :created] :created]]
                  :from :jars
                  :where [:= :group_name group-id]
                  :group-by [:group_name :jar_name]} :l]
                [:and
                 [:= :j/jar_name :l/jar_name]
                 [:= :j/group_name :l/group_name]
                 [:= :j/created :l/created]]]

         :left-join [;; Find the created ts for latest release
                     [{:select [:jar_name :group_name [[:max :created] :created]]
                       :from :jars
                       :where [:and
                               [:= :group_name group-id]
                               [:not [:like :version [:inline "%-SNAPSHOT"]]]]
                       :group-by [:group_name :jar_name]} :r]
                     [:and
                      [:= :j/jar_name :r/jar_name]
                      [:= :j/group_name :r/group_name]]

                     ;; Find version for latest release
                     [{:select [:jar_name :group_name :version :created]
                       :from :jars
                       :where [:= :group_name group-id]} :r2]
                     [:and
                      [:= :j/jar_name :r2/jar_name]
                      [:= :j/group_name :r2/group_name]
                      [:= :r/created :r2/created]]]
         :where [:= :j/group_name group-id]
         :order-by [[:j/group_name :asc]
                    [:j/jar_name :asc]]}))

(defn- find-jars-information*
  [db group-id artifact-id]
  (db/q db
        {:select [:j/jar_name :j/group_name :homepage :description
                  :user [:j/version :latest_version] [:r2/version :latest_release]
                  :licenses :scm]
         :from [[:jars :j]]
         ;; find the latest version
         :join [[{:select [:jar_name :group_name [[:max :created] :created]]
                  :from :jars
                  :where [:and
                          [:= :group_name group-id]
                          [:= :jar_name artifact-id]]
                  :group-by [:group_name :jar_name]} :l]
                [:and
                 [:= :j/jar_name :l/jar_name]
                 [:= :j/group_name :l/group_name]
                 [:= :j/created :l/created]]]

         :left-join [;; Find the created ts for latest release
                     [{:select [:jar_name :group_name [[:max :created] :created]]
                       :from :jars
                       :where [:and
                               [:= :group_name group-id]
                               [:= :jar_name artifact-id]
                               [:not [:like :version [:inline "%-SNAPSHOT"]]]]
                       :group-by [:group_name :jar_name]} :r]
                     [:and
                      [:= :j/jar_name :r/jar_name]
                      [:= :j/group_name :r/group_name]]

                     ;; Find version for latest release
                     [{:select [:jar_name :group_name :version :created]
                       :from :jars
                       :where [:and
                               [:= :group_name group-id]
                               [:= :jar_name artifact-id]]}
                      :r2]
                     [:and
                      [:= :j/jar_name :r2/jar_name]
                      [:= :j/group_name :r2/group_name]
                      [:= :r/created :r2/created]]]
         :where [:and
                 [:= :j/group_name group-id]
                 [:= :j/jar_name artifact-id]]
         :order-by [[:j/group_name :asc]
                    [:j/jar_name :asc]]}))

(defn find-jars-information
  ([db group-id]
   (find-jars-information db group-id nil))
  ([db group-id artifact-id]
   (if artifact-id
     (find-jars-information* db group-id artifact-id)
     (find-groups-jars-information db group-id))))

(defn find-jar-artifact
  [db group-id artifact-id]
  (some-> (find-jars-information db group-id artifact-id)
          first
          (update :licenses safe-edn-read str-map-vector)
          (update :scm safe-edn-read str-map)))

(defn all-groups [db]
  (db/q db {:select-distinct [:group_name]
            :from :jars
            :order-by [[:group_name :asc]]}))

(defn find-dependencies
  [db groupname jarname version]
  (db/q db
        {:select :*
         :from :deps
         :where [:and
                 [:= :group_name groupname]
                 [:= :jar_name jarname]
                 [:= :version version]]}))

(defn find-jar-dependents
  [db groupname jarname]
  (db/q db
        {:select :*
         :from :deps
         :where [:and
                 [:= :dep_group_name groupname]
                 [:= :dep_jar_name jarname]]
         :order-by [[:id :desc]]}))

