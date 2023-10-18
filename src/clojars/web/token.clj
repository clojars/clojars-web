(ns clojars.web.token
  (:require
   [clojars.auth :as auth]
   [clojars.web.common :refer [flash format-timestamp form-table html-doc error-list]]
   [clojars.web.safe-hiccup :refer [form-to]]
   [clojure.string :as str]
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

(def ^:private expiry-options
  [["Never"   ""]
   ["1 Hour"  "1"]
   ["1 Day"   "24"]
   ["1 Week"  "168"]
   ["30 days" "720"]
   ["90 days" "2160"]])

(defn show-tokens
  ([account tokens jars groups]
   (show-tokens account tokens jars groups nil))
  ([account tokens jars groups {:keys [error message new-token]}]
   (html-doc
    "Deploy Tokens"
    {:account account
     :description "Clojars deploy tokens"
     :extra-js ["/js/tokens.js"]}
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
    [:div.add-token.col-xs-12.col-sm-12
     [:h2 "Create Deploy Token"]
     [:p [:strong "Note:"]
      " the token value will only be shown once after it is created, so be sure to copy it."]
     (form-table
      [:post "/tokens/"]
      [[(label :name "Token name")
        (text-field {:placeholder "Laptop deploy token"
                     :required true}
                    :name)]
       [(label :scope "Token scope")
        (drop-down :scope (scope-options jars groups))]
       [(label :single_use "Single use?")
        (check-box :single_use)]
       [(label :expires_in "Expires in")
        (drop-down :expires_in expiry-options)]]
      (submit-button "Create Token"))]
    [:div.token-table.col-xs-12.col-sm-12
     [:h2 "Existing Deploy Tokens"]
     [:div
      [:span "Show: "]
      [:span "disabled tokens?" (check-box :show-disabled)]
      [:span "expired tokens?" (check-box :show-expired)]
      [:span "used single-use tokens?" (check-box :show-used)]]
     [:table.table.deploy-tokens
      [:thead
       [:tr
        [:th "Token Name"]
        [:th "Scope"]
        [:th "Single Use?"]
        [:th "Expires"]
        [:th "Created"]
        [:th "Disabled"]
        [:th "Last Used"]
        [:th "Actions"]]]
      [:tbody
       (for [token tokens
             :let [disabled? (:disabled token)
                   expired? (auth/token-expired? token)
                   used? (= "used" (:single_use token))
                   classes (str/join " "
                                     (remove nil?
                                             [(when disabled? "token-disabled")
                                              (when expired? "token-expired")
                                              (when used? "token-used")]))]]
         [:tr {:class classes}
          [:td.name (:name token)]
          [:td.scope (scope token)]
          [:td.single-use (:single_use token)]
          [:td.expires (format-timestamp (:expires_at token))]
          [:td.created (format-timestamp (:created token))]
          [:td.updated (when disabled? (format-timestamp (:updated token)))]
          [:td.last-used (format-timestamp (:last_used token))]
          [:td.actions (when-not (or disabled?
                                     expired?
                                     used?)
                         (form-to [:delete (str "/tokens/" (:id token))]
                                  [:input.button {:type "submit" :value "Disable Token"}]))]])]]])))
