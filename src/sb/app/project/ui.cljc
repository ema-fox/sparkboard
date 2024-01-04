(ns sb.app.project.ui
  (:require [inside-out.forms :as forms]
            [re-db.api :as db]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.data :as field.data]
            [sb.app.field.ui :as field.ui]
            [sb.app.project.data :as data]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.routing :as routing]
            [sb.app.views.ui :as ui]
            [sb.icons :as icons]
            [sb.app.views.radix :as radix]
            [sb.schema :as sch]
            [sb.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(def btn (v/from-element :div.btn.btn-transp.border-2.py-2.px-3))
(def hint (v/from-element :div.flex.items-center.text-sm {:class "text-primary/70"}))
(def chiclet (v/from-element :div.rounded.px-2.py-1 {:class "bg-primary/5 text-primary/90"}))

(ui/defview manage-community-actions [project actions]
  (forms/with-form [!actions (?actions :many {:community-action/label  ?label
                                              :community-action/action ?action
                                              :community-action/hint   ?hint})]
    (let [action-picker (fn [props]
                          [radix/select-menu
                           (v/merge-props props
                                          {:id              :project-action
                                           :placeholder     [:span.text-gray-500 (t :tr/choose-action)]
                                           :field/can-edit? true
                                           :field/options   [{:text  (t :tr/copy-link)
                                                              :value "LINK"}
                                                             {:text  (t :tr/start-chat)
                                                              :value "CHAT"}]})])
          add-btn       (fn [props]
                          (v/x [:button.p-3.text-gray-500.items-center.inline-flex.btn-darken.flex-none.rounded props
                                [icons/plus "icon-sm scale-125"]]))
          sample        (fn [label action]
                          (when-not (some #{label} (map (comp deref '?label) ?actions))
                            (v/x
                              [:<>
                               [:div.default-ring.rounded.inline-flex.divide-x.bg-white.items-center
                                [:div.p-3.whitespace-nowrap label]]
                               [action-picker {:value (some-> action str) :disabled true}]
                               [:div.flex [add-btn {:on-click #(forms/add-many! ?actions {:community-action/label  label
                                                                                          :community-action/action action})}]]])))]
      [:section.flex-v.gap-3
       [:div
        [:div.font-semibold.text-lg (t :tr/community-actions)]]
       (when-let [actions (seq ?actions)]
         [:div.flex.flex-wrap.gap-3
          (seq (for [{:syms [?label ?action ?hint]} actions]
                 [radix/tooltip @?hint
                  [:div.default-ring.default-ring-hover.rounded.inline-flex.items-center.divide-x
                   {:key @?label}
                   [:div.p-3.whitespace-nowrap @?label]]]))])
       [:div.bg-gray-100.rounded.p-3.gap-3.flex-v
        [:div.text-base.text-gray-500 (t :tr/community-actions-add)]
        [:div.grid.gap-3 {:style {:grid-template-columns "0fr 0fr 1fr"}}
         (sample (str "🔗 " (t :tr/share)) "LINK")
         (sample (str "🤝 " (t :tr/join-our-team)) "CHAT")
         (sample (str "💰 " (t :tr/invest)) "CHAT")
         (forms/with-form [!new-action {:community-action/label ?label :community-action/action ?action :community-action/hint ?hint}]
           (let [!label-ref (h/use-ref)]
             [:form.contents {:on-submit
                              (fn [^js e]
                                (.preventDefault e)
                                (forms/add-many! ?actions @!new-action)
                                (forms/clear! !new-action)
                                (.focus @!label-ref))}
              [:input.default-ring.form-text.rounded.p-3
               {:ref         !label-ref
                :value       (or @?label "")
                :on-change   (forms/change-handler ?label)
                :style       {:min-width 150}
                :placeholder (str (t :tr/other) "...")}]
              [action-picker {}]
              [:div.flex.gap-3
               (when @?label
                 [:input.text-gray-500.bg-gray-200.rounded.p-3.flex-auto.focus-ring
                  {:value       (or @?hint "")
                   :on-change   (forms/change-handler ?hint)
                   :style       {:min-width 150}
                   :placeholder (t :tr/hover-text)}])
               [add-btn {:type "submit"}]]]))]]

       ])))

#?(:cljs
   (defn use-dev-panel [entity]
     (let [!dev-edit? (h/use-state nil)
           can-edit?  (if-some [edit? @!dev-edit?]
                        edit?
                        (validate/editing-role? (:member/roles entity)))]
       [can-edit? (when (ui/dev?)
                    [radix/select-menu {:value           @!dev-edit?
                                        :on-value-change (partial reset! !dev-edit?)
                                        :field/classes {:trigger "flex items-center px-2 icon-gray text-sm self-start"
                                                        :content (str radix/menu-content-classes " text-sm")}
                                        :field/can-edit? true
                                        :field/options   [{:value nil :text "Current User"}
                                                          {:value true :text "Editor"}
                                                          {:value false :text "Viewer"}]}])])))

(def title-icon-classes "px-1 py-2 icon-light-gray")

(def modal-close [radix/dialog-close
                  [:div {:class title-icon-classes} [icons/close]]])

(ui/defview show
  {:route       "/p/:project-id"
   :view/router :router/modal}
  [params]
  (let [{:as          project
         :entity/keys [title
                       description
                       video
                       field-entries]
         :keys        [project/badges
                       member/roles]} (data/show params)
        [can-edit? dev-panel] (use-dev-panel project)
        field-params {:member/roles    roles
                      :field/can-edit? can-edit?}]
    [:<>

     [:div.flex-v.gap-6.pb-6
      ;; title row
      [:div.flex

       [:h1.font-medium.text-2xl.flex-auto.px-body.flex.items-center.pt-6
        (entity.ui/use-persisted-attr project :entity/title (merge field-params
                                                                   {:field/label     false
                                                                    :field/unstyled? true}))]

       dev-panel
       [:div.flex.self-start.ml-auto.px-1.rounded-bl-lg.border-b.border-l.relative

        [radix/tooltip "Back to board"
         [:a {:class title-icon-classes
              :href  (routing/entity-path (:entity/parent project) 'ui/show)}
          [icons/arrow-left]]]
        (when (:entity/id project)
          [radix/tooltip "Link to project"
           [:a {:class title-icon-classes
                :href  (routing/entity-path project :show)}
            [icons/link-2]]])
        modal-close]]

      [:div.px-body.flex-v.gap-6
       (entity.ui/use-persisted-attr project :entity/description (merge field-params {:field/label false}))
       (when badges
         [:section
          (into [:ul]
                (map (fn [bdg] [:li.rounded.bg-badge.text-badge-txt.py-1.px-2.text-sm.inline-flex (:badge/label bdg)]))
                badges)])
       (entity.ui/use-persisted-attr project
                                     :entity/field-entries
                                     {:entity/fields   (->> project :entity/parent :board/project-fields)
                                      :member/roles    roles
                                      :field/can-edit? can-edit?})
       [:section.flex-v.gap-2.items-start
        [manage-community-actions project (:project/community-actions project)]]
       (when video
         [field.ui/show-video video])]]]))
