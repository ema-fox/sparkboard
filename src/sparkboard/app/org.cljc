(ns sparkboard.app.org
  (:require #?(:clj [sparkboard.server.datalevin :as dl])
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.domain :as domain]
            [sparkboard.app.member :as member]
            [sparkboard.authorize :as az]
            [sparkboard.entity :as entity]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.query :as q]
            [sparkboard.routes :as routes]
            [sparkboard.schema :as sch :refer [? s-]]
            [sparkboard.ui :as ui]
            [sparkboard.ui.header :as header]
            [sparkboard.ui.icons :as icons]
            [sparkboard.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(sch/register!
  {:org/show-org-tab?          {:doc "Boards should visibly link to this parent organization"
                                s-   :boolean}
   :entity/id                  sch/unique-uuid
   :org/default-board-template (merge {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}
                                      (sch/ref :one))
   :org/as-map                 {s- [:map {:closed true}
                                    :entity/id
                                    :entity/title
                                    :entity/kind
                                    :entity/created-by
                                    (? :image/avatar)
                                    (? :image/background)
                                    (? :image/sub-header)
                                    (? :org/show-org-tab?)
                                    (? :entity/description)
                                    (? :entity/social-feed)
                                    (? :entity/locale-default)
                                    (? :entity/locale-suggestions)
                                    (? :entity/domain)
                                    (? :entity/public?)
                                    (? :org/default-board-template)
                                    (? :entity/created-at)]}})

(q/defx db:delete!
  "Mutation fn. Retracts organization by given org-id."
  {:endpoint {:post ["/o/" ['entity/id :org-id] "/delete"]}}
  [_req {:keys [org-id]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity org-id]])
  {:body ""})

(q/defquery db:edit
  [{:keys [org-id]}]
  ;; all the settings that can be changed
  (q/pull `[~@entity/fields]
          org-id))

(q/defquery db:read
  {:prepare [az/with-account-id!
             (member/member:log-visit! :org-id)]}
  [{:keys [org-id]}]
  (q/pull `[~@entity/fields
            {:board/_owner ~entity/fields}]
          (dl/resolve-id org-id)))

(q/defx db:search-once
  [{:as   params
    :keys [org-id q]}]
  (when q
    {:q        q
     :boards   (dl/q (u/template
                       `[:find [(pull ?board ~entity/fields) ...]
                         :in $ ?terms ?org
                         :where
                         [?board :board/owner ?org]
                         [(fulltext $ ?terms {:top 100}) [[?board ?a ?v]]]])
                     q
                     org-id)
     :projects (->> (dl/q (u/template
                            `[:find [(pull ?project [~@entity/fields
                                                     :project/sticky?
                                                     {:project/board [:entity/id]}]) ...]
                              :in $ ?terms ?org
                              :where
                              [?board :board/owner ?org]
                              [?project :project/board ?board]
                              [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]])
                          q
                          org-id)
                    (remove :project/sticky?))}))


(q/defquery db:edit!
  {:endpoint {:post ["/o/" ['entity/id :org-id] "/settings"]}}
  [{:keys [account]} {:keys [org-id]
                      org   :body}]
  (let [org (entity/conform (assoc org :entity/id org-id) :org/as-map)]
    (db/transact! [org])
    {:body org}))


(q/defquery db:new!
  {:endpoint {:post ["/o/" "new"]}}
  [{:keys [account]} {org :body}]
  (let [org    (-> (dl/new-entity org :org :by (:db/id account))
                   (entity/conform :org/as-map))
        member (-> {:member/entity  org
                    :member/account (:db/id account)
                    :member/roles   #{:role/owner}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    {:body org}))

(ui/defview show
  {:route ["/o/" ['entity/id :org-id]]}
  [params]
  (forms/with-form [_ ?q]
    (let [{:as   org
           :keys [entity/description]} (db:read params)
          q      (ui/use-debounced-value (u/guard @?q #(> (count %) 2)) 500)
          [result set-result!] (h/use-state nil)
          title (v/from-element :h3.font-medium.text-lg.pt-6)]
      (h/use-effect
        (fn []
          (when q
            (let [q q]
              (set-result! {:loading? true})
              (p/let [result (db:search-once {:org-id (:org-id params)
                                              :q      q})]
                (when (= q @?q)
                  (set-result! {:value result
                                :q     q}))))))
        [q])
      [:div

       (header/entity org
         [header/btn {:icon [icons/settings]
                      :href (routes/path-for 'sparkboard.app.org/edit params)}]
         #_[:div
            {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                      title "?"))
                          (routes/POST :org/delete params))}]

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
       [:div.p-body
        [ui/filter-field ?q {:loading? (:loading? result)}]
        [ui/error-view result]
        (if (seq q)
          (for [[kind results] (dissoc (:value result) :q)
                :when (seq results)]
            [:<>
             [title (tr (keyword "tr" (name kind)))]
             [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2
              (map entity/row results)]])
          [:div.flex.flex-col.gap-2
           [title (tr :tr/boards)]
           [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2
            (map entity/row (:board/_owner org))]])]])))

(ui/defview edit
  {:route ["/o/" ['entity/id :org-id] "/settings"]}
  [{:as params :keys [org-id]}]
  (let [org (q/use! '[sparkboard.app.org/db:edit params])]
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
       (header/entity org)

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
        [:a.btn.btn-primary.p-4 {:href (routes/entity org :show)} (tr :tr/done)]]])))

(ui/defview new
  {:route       ["/o/" "new"]
   :view/target :modal}
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