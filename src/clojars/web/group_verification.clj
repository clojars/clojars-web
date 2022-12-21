(ns clojars.web.group-verification
  (:require
   [clojars.web.common :refer [error-flash flash html-doc]]
   [clojars.web.safe-hiccup :refer [form-to]]
   [hiccup.element :refer [link-to unordered-list]]
   [hiccup.form :refer [label submit-button text-field]]))

(defn- flash-details
  [{:keys [domain group txt-records]}]
  [:div#details
   [:p "Group: " group]
   [:p "Domain: " domain]
   (when txt-records
     (list
      [:p "TXT records:"
       (unordered-list
        (map (fn [r] [:code r]) txt-records))]))])

(defn- format-flash
  [{:as flash-data :keys [error message]}]
  (cond
    message (flash [:h2 message]
                   (flash-details flash-data))
    error   (error-flash [:h2 error]
                         (flash-details flash-data))))

(defn- TXT-help
  [account]
  (let [txt-record [:code (format "\"clojars %s\"" account)]]
    (list
     [:p "In order to verify a reverse-domain-based group that is based on a domain
  you control, you'll need to create a TXT DNS record on the domain of "
      txt-record
      ", then enter the group and domain below. Clojars will then look up the
      TXT records for the given domain, validate the group, and then verify it
      if everything checks out."]
     [:p "This will create the group if it doesn't already exist, and can also
     be used to verify an existing group."]
     [:p "For example, if you are trying to verify the group "
      [:code "com.example"]
      ", you would add a TXT DNS record of "
      txt-record
      " to the example.com domain."]
     [:p "See "
      (link-to "https://github.com/clojars/clojars-web/wiki/Verified-Group-Names"
               "the wiki")
      " for more details on verified group names."]
     [:p "Do a search for "
      [:code "TXT DNS <your DNS provider>"]
      " if you are unsure how to add a TXT DNS record for your domain."]
     [:p "Please "
      (link-to "https://github.com/clojars/administration/issues/new/choose"
               "an issue")
      " if you run in to any problems verifying your group name."])))

(defn index
  [account flash-data]
  (html-doc
   "Group Verification"
   {:account account}
   [:div.col-xs-6
    [:h1 "Group Verification"]
    (format-flash flash-data)
    [:div
     [:h2 "Verification by TXT Record"]
     [:details.help
      [:summary "Help"]
      (TXT-help account)]
     (form-to [:post "/verify/group/txt"]
              (label :group "Group name")
              (text-field {:required    true
                           :placeholder "com.example"}
                          :group)
              (label :domain "Domain with TXT record")
              (text-field {:required    true
                           :placeholder "example.com"}
                          :domain)
              (submit-button "Verify Group"))]]))
