(ns clojars.web.group
  (:require
   [clojars.db :as db]
   [clojars.web.common :refer [audit-table form-table html-doc jar-link user-link error-list verified-group-badge-small]]
   [clojars.web.safe-hiccup :refer [form-to]]
   [clojars.web.structured-data :as structured-data]
   [hiccup.element :refer [unordered-list]]
   [hiccup.form :refer [text-field hidden-field select-options]]))

(defn- scope-options
  [db account groupname actives all-group-jars]
  (let [admin-scopes-for-user (db/admin-group-scopes-for-user db account groupname)
        admin-all? (contains? admin-scopes-for-user db/SCOPE-ALL)
        all-visible-jar-scopes (into #{}
                                     (comp
                                      (filter #(or
                                                admin-all?
                                                (contains? admin-scopes-for-user %)))
                                      (remove #(= db/SCOPE-ALL %)))
                                     (concat
                                      (map :jar_name all-group-jars)
                                      (map :scope actives)))]
    (into (if admin-all?
            [["All Projects"   "*"]
             ["New Project..." ":new"]]
            [])
          (map #(vector % %))
          all-visible-jar-scopes)))

(defn show-group
  [db account groupname & errors]
  (let [actives (db/group-actives db groupname)
        actives-for-user (db/group-actives-for-user db groupname account)
        user-admin-scopes (into #{}
                                (comp
                                 (filter #(= account (:user %)))
                                 (filter :admin)
                                 (map :scope))
                                actives-for-user)
        current-user-admin? (seq user-admin-scopes)
        current-user-admin-for-scope? (fn [scope]
                                        (or (contains? user-admin-scopes db/SCOPE-ALL)
                                            (contains? user-admin-scopes scope)))
        show-membership-details? (seq actives-for-user)
        verified-group? (db/find-group-verification db groupname)
        group-settings (db/get-group-settings db groupname)
        all-group-jars (db/jars-by-groupname db groupname)]
    (html-doc
     (str groupname " group")
     {:account account
      :description (format "Clojars projects in the %s group" groupname)
      :extra-js ["/js/permissions.js"]}
     [:div.col-xs-12
      (structured-data/breadcrumbs [{:url  (str "https://clojars.org/groups/" groupname)
                                     :name groupname}])
      [:div#group-title
       [:h1 (str groupname " group")]
       (when (and verified-group? show-membership-details?)
         verified-group-badge-small)]
      [:h2 "Projects"]
      (unordered-list (map jar-link all-group-jars))
      [:h2 "Permissions"]
      (if show-membership-details?
        (list
         [:details.help
          [:summary "Help"]
          [:p "A user that has a permission in a group can deploy new versions
           of projects that are within the group. Users scoped to All
           Projects (*) can deploy any existing project within the group, and
           can deploy new projects. A user scoped to one or more projects can
           only deploy to those projects."]
          [:p "An Admin permission for a given project scope will allow the
           user (in addition to the right to deploy) to add permissions, but
           only within that scope. For example, an Admin user scoped to the
           'foo' project can only add new permissions scoped to 'foo'."]]
         [:table.group-member-list
          [:thead
           [:tr
            [:th "Username"]
            [:th "Scope"]
            [:th "Admin?"]]]
          [:tbody
           (for [active (sort-by :user actives)
                 :let [{:keys [admin scope user]} active]]
             [:tr
              [:td (user-link user)]
              [:td scope]
              [:td
               (if admin "Yes" "No")]
              (when (and (not= account user)
                         (current-user-admin-for-scope? scope))
                (list
                 [:td
                  (form-to [:post (str "/groups/" groupname)]
                           (hidden-field "username" user)
                           (hidden-field "scope_to_jar" scope)
                           (hidden-field "admin" (if admin 0 1))
                           [:input.button.green-button {:type "submit"
                                                        :value "Toggle Admin"}])]
                 [:td
                  (form-to [:delete (str "/groups/" groupname)]
                           (hidden-field "username" user)
                           (hidden-field "scope_to_jar" scope)
                           [:input.button.red-button {:type "submit"
                                                      :value "Remove Permission"}])]))])]])
        (unordered-list (->> (db/group-all-actives db groupname)
                             (into []
                                   (comp
                                    (map :user)
                                    (distinct)))
                             (sort)
                             (mapv user-link))))
      (error-list errors)
      (when current-user-admin?
        (list
         [:div.add-member
          [:h2 "Add permission to group"]
          (form-table
           [:post (str "/groups/" groupname)]
           [[[:label "Username "]
             (text-field "username")]
            [[:label "Admin? "]
             [:input {:type "checkbox"
                      :name "admin"
                      :id "admin"
                      :value 1
                      :checked false}]]
            [[:label "Scope to project "]
             [:span
              [:select
               {:name :scope_to_jar
                :id :scope_to_jar_select}
               (select-options (scope-options db account groupname actives all-group-jars))]
              " "
              (text-field {:placeholder "new project"}
                          :scope_to_jar_new)]]]
           [:input.button {:type "submit" :value "Add Permission"}])]
         ;; Only all-scoped admins can change group-wide settings
         (when (current-user-admin-for-scope? db/SCOPE-ALL)
           [:div.group-settings
            [:h2 "Group Settings"]
            (form-table
             [:post (format "/groups/%s/settings" groupname)]
             [[[:label "Require users to have two-factor auth enabled to deploy? "]
               [:input {:type "checkbox"
                        :name "require_mfa"
                        :id "require_mfa"
                        :value 1
                        :checked (:require_mfa_to_deploy group-settings false)}]]]
             [:input.button {:type "submit" :value "Update Settings"}])])))
      (when show-membership-details?
        (audit-table db groupname {:group-name groupname}))])))
