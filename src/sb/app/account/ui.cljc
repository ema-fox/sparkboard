(ns sb.app.account.ui
  (:require #?(:cljs ["@radix-ui/react-dropdown-menu" :as dm])
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.account.data :as data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.routing :as routing]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sb.authorize :as az]))

(defn account:sign-in-with-google []
  (v/x
    [:a.btn.btn-white
     {:class "w-full h-10 text-zinc-500 text-sm"
      :href  "/oauth2/google/launch"}
     [:img.w-5.h-5.m-2 {:src "/images/google.svg"}] (t :tr/continue-with-google)]))

(defn account:sign-in-terms []
  (v/x
    [:p.px-8.text-center.text-sm {:class "text-txt/70"} (t :tr/sign-in-agree-to)
     [:a.gray-link {:href "/documents/terms-of-service"} (t :tr/tos)] ","
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385/cookie-policy"} (t :tr/cookie-policy)]
     (t :tr/and)
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385"} (t :tr/privacy-policy)] "."]))

(ui/defview account:continue-with [{:keys [route]}]
  (ui/with-form [!account {:account/email    (?email :init "")
                           :account/password (?password :init "")}
                 :required [?email ?password]]
    (let [!step (h/use-state :email)]
      [:form.flex-grow.m-auto.gap-6.flex-v.max-w-sm.px-4
       {:on-submit (fn [^js e]
                     (.preventDefault e)
                     (case @!step
                       :email (do (reset! !step :password)
                                  (js/setTimeout #(.focus (js/document.getElementById "account-password")) 100))
                       :password (p/let [res (routing/POST 'sb.server.account/login! @!account)]
                                   (js/console.log "res" res)
                                   (prn :res res))))}


       [:div.flex-v.gap-2
        [field.ui/text-field ?email nil]
        (when (= :password @!step)
          [field.ui/text-field ?password {:id "account-password"}])
        (str (forms/visible-messages !account))
        [:button.btn.btn-primary.w-full.h-10.text-sm.p-3
         (t :tr/continue-with-email)]]

       [:div.relative
        [:div.absolute.inset-0.flex.items-center [:span.w-full.border-t]]
        [:div.relative.flex.justify-center.text-xs.uppercase
         [:span.bg-secondary.px-2.text-muted-txt (t :tr/or)]]]
       [account:sign-in-with-google]
       [account:sign-in-terms]])))

(ui/defview sign-in
  {:route "/login"}
  [params]
  (if (db/get :env/config :account-id)
    (ui/redirect `home)
    [:div.h-screen.flex-v
     [header/lang "absolute top-0 right-0 p-4"]
     [:div.flex-v.items-center.max-w-sm.mt-10.relative.mx-auto.py-6.gap-6
      {:class ["bg-secondary rounded-lg border border-txt/05"]}
      [:h1.text-3xl.font-medium.text-center (t :tr/welcome)]
      [radix/tab-root]
      [account:continue-with params]]]))

(ui/defview show
  {:route            "/"
   :endpoint/public? true}
  [{:keys [account-id]} params]
  (if account-id
    (let [?filter       (h/use-callback (forms/field))
          all           (data/all {})
          account       (db/get :env/config :account)
          {:as entities :keys [board org]} (-> (->> all (map :membership/entity) (group-by :entity/kind))
                                               (update-vals #(sort-by :entity/created-at u/compare:desc %))
                                               (u/guard seq))
          boards-by-org (group-by :entity/parent board)
          match-text    @?filter]
      [:div.divide-y
       [header/entity (data/account-as-entity account) nil]

       (when (seq entities)
         [:div.p-body.flex-v.gap-8
          (when (> (count all) 6)
            [field.ui/filter-field ?filter nil])
          (let [limit (partial ui/truncate-items {:limit 10})]
            (when (seq org)
              [:div.flex-v.gap-4
               (u/for! [org org
                        :let [projects-by-board (into {}
                                                      (keep (fn [board]
                                                              (when-let [projects (->> (az/membership account-id board)
                                                                                       :membership/_member
                                                                                       (into []
                                                                                             (comp (map :membership/entity)
                                                                                                   (ui/filtered @?filter)))
                                                                                       seq)]
                                                                [board projects])))
                                                      (get boards-by-org org))
                              boards            (into []
                                                      (filter (fn [board]
                                                                (or (contains? projects-by-board board)
                                                                    (ui/match-entity match-text board))))
                                                      (get boards-by-org org))]
                        :when (or (seq boards)
                                  (seq projects-by-board)
                                  (ui/match-entity match-text org))]
                 [:div.contents {:key (:entity/id org)}
                  [:a.text-lg.font-semibold.flex.items-center.hover:underline {:href (routing/entity-path org 'ui/show)} (:entity/title org)]
                  (limit
                    (u/for! [board boards
                             :let [projects (get projects-by-board board)]]
                      [:div.flex-v
                       [entity.ui/row board]
                       [:div.pl-14.ml-1.flex.flex-wrap.gap-2.mt-2
                        (u/for! [project projects]
                          [:a.bg-gray-100.hover:bg-gray-200.rounded.h-12.inline-flex.items-center.px-3.cursor-default
                           {:href (routing/entity-path project 'ui/show)}
                           (:entity/title project)])]]))])]))])
       [:div.p-body
        (when (and (empty? match-text) (empty? (:board entities)))
          [ui/hero
           (ui/show-markdown
             (t :tr/start-board-new))
           [:a.btn.btn-primary.btn-base {:class "mt-6"
                                         :href  (routing/path-for ['sb.app.board.ui/new])}
            (t :tr/create-first-board)]])]])
    (ui/redirect `sign-in)))