(ns clojars.verification
  (:require
   [clojars.db :as db]
   [clojars.web.common :as common]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

(defn- get-txt-records
  [domain]
  (let [{:keys [err exit out]} (shell/sh "dig" "txt" "+short" domain)]
    (if (= 0 exit)
      (-> out
          (str/replace "\"" "")
          (str/split #"\n"))
      (throw (ex-info "TXT lookup failed" {:status exit
                                           :stderr err
                                           :stdout out})))))

(defn- parse-text-records
  [txt]
  (let [[_ username] (some #(re-find #"^clojars[- ](.+)$" %) txt)]
    username))

(defn- valid-identifier?
  [s]
  (and (string? s)
       (some? (re-find #"\w[.]\w" s))
       (some? (re-find #"^[a-z0-9_.-]+$" s))))

;; This is intentionally more verbose than it needs to be for thoroughness &
;; documentation purposes
(defn group+domain-correspond?
  [group domain]
  (and (some? group)
       (some? domain)
       (let [group-parts (str/split group #"\.")
             domain-parts (reverse (str/split domain #"\."))
             ;; Some folks put the TXT record on a clojars subdomain, so we allow for that
             domain-parts (if (contains? #{"clojars" "_clojars"} (last domain-parts))
                            (butlast domain-parts)
                            domain-parts)]
         (and
          (<= 2 (count group-parts))
          (<= 2 (count domain-parts))
          (or
           (= group-parts domain-parts)
           ;; Allow verifying a sub-domain-based group.
           (= (take (count domain-parts) group-parts) domain-parts))))))

(defn- err
  [request msg]
  (assoc request :error msg))

(defn- verify-group
  [db request username group]
  ;; will only create the group if it doesn't already exist
  (db/add-group db username group)
  (db/verify-group! db username group)
  (assoc request :message (format "The group '%s' has been verified." group)))

(defn verify-group-by-TXT
  "Verifies a group after confirming that:

  * the group is a valid group
  * the group and domain correspond
  * the TXT record has a Clojars username
  * if the group exists, that username is an active member of the group"
  [db {:as request :keys [username domain group]}]
  (let [domain (str/lower-case domain)
        group  (str/lower-case group)]
    (cond
      (not (valid-identifier? group))
      (err request "The group name is not a valid reverse-domain name.")

      (not (valid-identifier? domain))
      (err request "The domain name is not a valid domain name.")

      (not (group+domain-correspond? group domain))
      (err request "Group and domain do not correspond with each other.")

      ;; This should never be hit currently, since we have no reverse-domain
      ;; groups in the reserved list. It is here in case we add one in the
      ;; future.
      (contains? db/reserved-names group)
      (err request (format "'%s' is a reserved group name." group))

      :else
      (let [txt                  (get-txt-records domain)
            request              (assoc request :txt-records txt)
            username-from-txt    (parse-text-records txt)
            group-active-members (db/group-activenames db group)
            group-verification   (db/find-group-verification db group)]
        (cond
          (not username-from-txt)
          (err request "No valid verification TXT record found.")

          (not (= username username-from-txt))
          (err request (format "Validation TXT record is for user '%s', not '%s' (you)."
                               username-from-txt username))

          group-verification
          (err request (format "Group already verified by user '%s' on %s."
                               (:verified_by group-verification)
                               (common/format-date (:created group-verification))))

          ;; The group does not exist
          (empty? group-active-members)
          (verify-group db request username group)

          ;; The group does exist, and the current user is a member
          (contains? (set group-active-members) username)
          (verify-group db request username group)

          :else
          (err request "You are not an active member of the group."))))))
