(ns clojars.web.token
  (:require
   [clojars.web.common :refer [flash html-doc error-list simple-date]]
   [clojars.web.safe-hiccup :refer [form-to]]
   [hiccup.form :refer [text-field submit-button]]))

(defn- new-token-message
  [{:keys [name token]}]
  (when token
    (list
     [:p (format "Your new deploy token '%s' has been created. It will only be shown this one time, so be sure to copy it now:"
                 name)]
     [:div.new-token
      [:pre token]])))

(defn show-tokens
  ([account tokens]
   (show-tokens account tokens nil))
  ([account tokens {:keys [error message new-token]}]
   (html-doc
    "Deploy Tokens"
    {:account account :description "Clojars deploy tokens"}
    [:div
     [:h1 "Deploy Tokens"]
     (error-list (when error [error]))
     (flash message)
     (flash (new-token-message new-token))
     [:div.add-token.col-xs-12.col-sm-6
      [:h2 "Create Depoy Token"]
      [:p "Note: the token value will only be shown once after it is created, so be sure to copy it."]
      (form-to [:post "/tokens/"]
               [:div
                [:label
                 "Token name "
                 (text-field "name")]]
               (submit-button "Create Token"))]
     [:div.token-table.col-xs-12.col-sm-12
      [:h2 "Existing Tokens"]
      [:table.table.table-striped.deploy-tokens
       [:thead
        [:tr
         [:th "Token Name"]
         [:th "Created"]
         [:th "Disabled"]
         [:th "Actions"]]]
       [:tbody
        (for [token (sort-by (juxt :disabled :name :created) tokens)
              :let [disabled? (:disabled token)]]
          [:tr
           [:td {:class (when disabled?  "token-disabled")} (:name token)]
           [:td (simple-date (:created token))]
           [:td (when disabled? (simple-date (:updated token)))]
           [:td (when-not disabled?
                  (form-to [:delete (str "/tokens/" (:id token))]
                           [:input.button {:type "submit" :value "Disable Token"}]))]])]]]])))
