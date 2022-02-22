(ns clojars.web.token
  (:require
   [clojars.web.common :refer [flash html-doc error-list simple-date]]
   [clojars.web.safe-hiccup :refer [form-to]]
   [hiccup.form :refer [check-box drop-down label text-field submit-button]]))

(defn- new-token-message
  [{:keys [name token]}]
  (when token
    (list
     [:p (format "Your new deploy token '%s' has been created. It will only be shown this one time, so be sure to copy it now:"
                 name)]
     [:div.new-token
      [:pre token]])))

(defn- scope
  [{:keys [group_name jar_name]}]
  (cond
    jar_name   (format "%s/%s" group_name jar_name)
    group_name (format "%s/*" group_name)))

(defn- scope-options
  [jars groups]
  (let [jars (group-by :group_name jars)
        groups (-> groups
                   (concat (keys jars))
                   distinct
                   sort)]
    (reduce
     (fn [acc group]
       (concat acc
               [[(scope {:group_name group}) group]]
               (when-some [jars (get jars group)]
                 (map scope (sort-by :jar_name jars)))))
     [["*" ""]]
     groups)))

(defn show-tokens
  ([account tokens jars groups]
   (show-tokens account tokens jars groups nil))
  ([account tokens jars groups {:keys [error message new-token]}]
   (html-doc
    "Deploy Tokens"
    {:account account :description "Clojars deploy tokens"}
    [:div
     [:h1 "Deploy Tokens"]
     (error-list (when error [error]))
     (flash message)
     (flash (new-token-message new-token))]
    [:div.col-xs-12.col-sm-12
     [:div.help
      [:p "A deploy token is used in place of a password when deploying, and cannot be used to log in. "
       "Tokens can be scoped to:"]
      [:ul
       [:li "any artifact you have access to ('*')"]
       [:li "any artifact within a group you have access to ('group-name/*')"]
       [:li "a particular artifact you have access to ('group-name/artifact-name')"]]]]
    [:div.token-table.col-xs-12.col-sm-12
     [:table.table.deploy-tokens
      [:thead
       [:tr
        [:th "Token Name"]
        [:th "Scope"]
        [:th "Single Use?"]
        [:th "Created"]
        [:th "Disabled"]
        [:th "Last Used"]
        [:th "Actions"]]]
      [:tbody
       (for [token (sort-by (juxt :disabled :name :created) tokens)
             :let [disabled? (:disabled token)]]
         [:tr
          [:td.name {:class (when disabled?  "token-disabled")} (:name token)]
          [:td.scope (scope token)]
          [:td.single-use (:single_use token)]
          [:td.created (simple-date (:created token))]
          [:td.updated (when disabled? (simple-date (:updated token)))]
          [:td.last-used (simple-date (:last_used token))]
          [:td.actions (when-not disabled?
                         (form-to [:delete (str "/tokens/" (:id token))]
                                  [:input.button {:type "submit" :value "Disable Token"}]))]])]]]
    [:div.add-token.small-section.col-xs-12.col-sm-6
     [:h2 "Create Deploy Token"]
     [:p [:strong "Note:"]
      " the token value will only be shown once after it is created, so be sure to copy it."]
     (form-to [:post "/tokens/"]
              (label :name "Token name")
              (text-field {:placeholder "Laptop deploy token"
                           :required true}
                          :name)
              (label :scope "Token scope")
              (drop-down :scope (scope-options jars groups))
              (label :single_use "Single use?")
              (check-box :single_use)
              (submit-button "Create Token"))])))
