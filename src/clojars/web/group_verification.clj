(ns clojars.web.group-verification
  (:require
   [clojars.web.common :refer [error-flash flash html-doc]]
   [clojars.web.safe-hiccup :refer [form-to]]
   [hiccup.element :refer [link-to unordered-list]]
   [hiccup.form :refer [label submit-button text-field]]))

(defn- flash-details
  [{:keys [domain group parent-group txt-records url]}]
  [:div#details
   (when group
     [:p "Group: " group])
   (when domain
     [:p "Domain: " domain])
   (when parent-group
     [:p "Parent group: " parent-group])
   (when txt-records
     (list
      [:p "TXT records:"
       (unordered-list
        (map (fn [r] [:code r]) txt-records))]))
   (when url
     [:p "URL: " url])])

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
     [:p "Do a search for "
      [:code "TXT DNS <your DNS provider>"]
      " if you are unsure how to add a TXT DNS record for your domain."])))

(defn- vcs-help
  [account]
  (let [repo-name (format "clojars-%s" account)]
    (list
     [:p "Clojars supports groups that are based on a GitHub or Gitlab
      organization. For example, if you own "
      [:code "https://github.com/example-org/"] " or "
      [:code "https://gitlab.com/example-org/"] ", you can have the groups "
      [:code "com.github.example-org"] " & "
      [:code "io.github.example-org"] " or "
      [:code "com.gitlab.example-org"] " & "
      [:code "io.gitlab.example-org"] ", respectively."]
     [:p
      " You can verify ownership of the organization by creating
      a " [:strong "public"] " repo named "
      [:code repo-name]
      ", then provide the full URL to that repo below. Clojars will look up
      the repo, and then verify the corresponding group names if everything
      checks out."]
     [:p
      "This will create the groups if they don't already exist, and can also be
      used to verify existing groups."]
     [:p [:strong "Note:"]
      "You can also use this method to verify domains based on your github
      username, but you can also verify those by using the \"Login with GitHub\"
      or \"Login with Gitlab\" feature on the Clojars login page."])))

(def ^:private parent-help
  (list
   [:p "A subgroup is based on a (potential) subdomain of another group. To
  verify it, the parent group has to be verified."]
   [:p "This will create the group if it doesn't already exist, and can also
     be used to verify an existing group."]
   [:p "For example, if you are trying to verify the subgroup "
    [:code "com.example.ham"]
    ", and you have already verified "
    [:code "com.example"]
    ", then you can use this verification option."]
   [:p "If you haven't verified the parent group and don't need to, you can
   verify the group using the TXT record method above, but note that the TXT
   record will need to be on the corresponding subdomain."]))

(defn index
  [account flash-data]
  (let [heading (format "Group Verification (%s)" account)]
    (html-doc
     heading
     {:account account}
     [:div.col-xs-6
      [:h1 heading]
      (format-flash flash-data)
      [:div.help
       [:p "See "
        (link-to "https://github.com/clojars/clojars-web/wiki/Verified-Group-Names"
                 "the wiki")
        " for more details on verified group names. Please file "
        (link-to "https://github.com/clojars/administration/issues/new/choose"
                 "an issue")
        " if you run in to any problems verifying your group name."]]
      [:hr]
      [:div.via-txt
       [:h2 "Verification by TXT Record"]
       [:div.row
        [:div.col-xs-5
         (form-to [:post "/verify/group/txt"]
                  (label :group "Group name")
                  (text-field {:required    true
                               :placeholder "com.example"}
                              :group)
                  (label :domain "Domain with TXT record")
                  (text-field {:required    true
                               :placeholder "example.com"}
                              :domain)
                  (submit-button "Verify Group"))]
        [:div.col-xs-7
         [:div.help (TXT-help account)]]]]
      [:hr]
      [:div.via-vcs
       [:h2 "Verification of GitHub/Gitlab Repo Groups"]
       [:div.row
        [:div.col-xs-5
         (form-to [:post "/verify/group/vcs"]
                  (label :url "Verification Repository URL")
                  (text-field {:required    true
                               :placeholder (format "https://github.com/example/clojars-%s"
                                                    account)}
                              :url)
                  (submit-button "Verify Groups"))]
        [:div.col-xs-7
         [:div.help (vcs-help account)]]]]
      [:hr]
      [:div.via-parent
       [:h2 "Verification by Parent Group"]
       [:div.row
        [:div.col-xs-5
         (form-to [:post "/verify/group/parent"]
                  (label :group "Group name")
                  (text-field {:required    true
                               :placeholder "com.example.ham"}
                              :group)
                  (submit-button "Verify Group"))]
        [:div.col-xs-7
         [:div.help parent-help]]]]])))
