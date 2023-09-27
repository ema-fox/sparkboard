(ns sparkboard.app.org
  (:require #?(:clj [sparkboard.app.member :as member])
            #?(:clj [sparkboard.server.datalevin :as dl])
            [inside-out.forms :as forms]
            [sparkboard.app.domains :as domain]
            [sparkboard.entity :as entity]
            [sparkboard.ui.icons :as icons]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.ui :as ui]
            [sparkboard.websockets :as ws]
            [re-db.api :as db]))

#?(:clj
   (defn db:delete!
     "Mutation fn. Retracts organization by given org-id."
     {:endpoint {:post ["/o/" ['uuid :org-id] "/delete"]}}
     [_req {:keys [org-id]}]
     ;; auth: user is admin of org
     ;; todo: retract org and all its boards, projects, etc.?
     (db/transact! [[:db.fn/retractEntity [:entity/id org-id]]])
     {:body ""}))

#?(:clj
   (defn db:edit
     {:endpoint {:query ["/o/" ['uuid :org-id] "/settings"]}}
     [{:keys [org-id]}]
     ;; all the settings that can be changed
     (db/pull `[~@entity/fields]
              [:entity/id org-id])))

#?(:clj
   (defn db:read
     {:endpoint  {:query ["/o/" ['uuid :org-id]]}
      :authorize (fn [req {:as params :keys [org-id]}]
                   (member/member:read-and-log! org-id (:db/id (:account req)))
                   ;; TODO make sure user has permission?
                   params)}
     [{:as params :keys [org-id]}]
     (db/pull `[~@entity/fields
                {:board/_owner ~entity/fields}]
              (dl/resolve-id org-id))))

#?(:clj
   (defn db:search
     {:endpoint {:query ["/o/" ['uuid :org-id] "/search"]}}
     [{:as   params
       :keys [org-id q]}]
     (when q
       {:q        q
        :boards   (dl/q (u/template
                          [:find [(pull ?board ~entity/fields) ...]
                           :in $ ?terms ?org
                           :where
                           [?board :board/owner ?org]
                           [(fulltext $ ?terms {:top 100}) [[?board ?a ?v]]]])
                        q
                        [:entity/id org-id])
        :projects (->> (dl/q (u/template
                               [:find [(pull ?project [~@entity/fields
                                                       :project/sticky?
                                                       {:project/board [:entity/id]}]) ...]
                                :in $ ?terms ?org
                                :where
                                [?board :board/owner ?org]
                                [?project :project/board ?board]
                                [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]])
                             q
                             [:entity/id org-id])
                       (remove :project/sticky?))})))

#?(:clj
   (defn db:edit!
     {:endpoint {:post ["/o/" ['uuid :org-id] "/settings"]}}
     [{:keys [account]} {:keys [org-id]
                         org   :body}]
     (let [org (entity/conform (assoc org :entity/id org-id) :org/as-map)]
       (db/transact! [org])
       {:body org})))

#?(:clj
   (defn db:new!
     {:endpoint {:post ["/o/" "new"]}}
     [{:keys [account]} {org :body}]
     (let [org    (-> (dl/new-entity org :org :by (:db/id account))
                      (entity/conform :org/as-map))
           member (-> {:member/entity  org
                       :member/account (:db/id account)
                       :member/roles   #{:role/owner}}
                      (dl/new-entity :member))]
       (db/transact! [member])
       {:body org})))

(ui/defview read
            {:endpoint {:view ["/o/" ['uuid :org-id]]}}
            [params]
            (forms/with-form [_ ?q]
              (let [{:as   org
                     :keys [entity/description]} (ws/use-query! ['sparkboard.app.org/db:read params])
                    q      (ui/use-debounced-value (u/guard @?q #(> (count %) 2)) 500)
                    result (ws/use-query ['sparkboard.app.org/db:search {:org-id (:org-id params)
                                                                         :q      q}])]
                [:div
                 (ui/entity-header org
                                   [ui/header-btn [icons/settings]
                                    (routes/path-for 'sparkboard.app.org/edit params)]
                                   #_[:div
                                      {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                                                title "?"))
                                                    (routes/POST :org/delete params))}]
                                   [ui/filter-field ?q {:loading? (:loading? result)}]
                                   [:a.btn.btn-light {:href (routes/href 'sparkboard.app.board/new
                                                                         {:query-params {:org-id (:entity/id org)}})} (tr :tr/new-board)])

                 [:div.p-body.whitespace-pre
                  "This is the landing page for an organization. Its purpose is to provide a quick overview of the organization and list its boards.
                   - show hackathons by default. sort-by date, group-by year.
                   - tabs: hackathons, projects, [about / external tab(s)]
                   - search

                   "
                  ]
                 [:div.p-body (ui/show-prose description)]
                 [ui/error-view result]
                 (if (seq q)
                   (for [[kind results] (dissoc (:value result) :q)
                         :when (seq results)]
                     [:<>
                      [:h3.px-body.font-bold.text-lg.pt-6 (tr (keyword "tr" (name kind)))]
                      [:div.card-grid (map entity/card:compact results)]])
                   [:div.card-grid (map entity/card:compact (:board/_owner org))])])))

(ui/defview edit
            {:endpoint {:view ["/o/" ['uuid :org-id] "/settings"]}}
            [{:as params :keys [org-id]}]
            (let [org (ws/use-query! '[sparkboard.app.org/db:edit params])]
              (forms/with-form [!org (u/keep-changes org
                                                     {:entity/id          org-id
                                                      :entity/title       (?title :label (tr :tr/title))
                                                      :entity/description (?description :label (tr :tr/description))
                                                      :entity/domain      ?domain
                                                      :image/avatar       (?logo :label (tr :tr/image.logo))
                                                      :image/background   (?background :label (tr :tr/image.background))})
                                :validators {?domain [domain/domain-valid-string
                                                      (domain/domain-availability-validator)]}
                                :init org
                                :form/auto-submit #(routes/POST [:org/edit params] %)]
                [:<>
                 (ui/entity-header org)

                 [:div {:class ui/form-classes}

                  (ui/show-field-messages !org)
                  (ui/text-field ?title)
                  (ui/prose-field ?description)
                  (domain/show-domain-field ?domain)
                  [:div.flex.flex-col.gap-2
                   [ui/input-label {} (tr :tr/images)]
                   [:div.flex.gap-6
                    (ui/image-field ?logo)
                    (ui/image-field ?background)]]
                  [:a.btn.btn-primary.p-4 {:href (routes/entity org :read)} (tr :tr/done)]]])))

(ui/defview new
            {:endpoint {:view ["/o/" "new"]}
             :target   :modal}
            [params]
            (forms/with-form [!org (u/prune
                                     {:entity/title  ?title
                                      :entity/domain ?domain})
                              :required [?title ?domain]]
              [:form
               {:class     ui/form-classes
                :on-submit (fn [e]
                             (.preventDefault e)
                             (ui/with-submission [result (routes/POST [:org/new params] @!org)
                                                  :form !org]
                                                 (routes/set-path! :org/read {:org-id (:entity/id result)})))}
               [:h2.text-2xl (tr :tr/new-org)]
               (ui/show-field ?title {:label (tr :tr/title)})
               (domain/show-domain-field ?domain)
               (ui/show-field-messages !org)
               [ui/submit-form !org (tr :tr/create)]]))