(ns sparkboard.app.field
  (:require [applied-science.js-interop :as j]
            [clojure.set :as set]
            [clojure.string :as str]
            [inside-out.forms :as forms]
            [sparkboard.app.entity :as entity]
            [sparkboard.authorize :as az]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.schema :as sch :refer [? s-]]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.ui :as ui]
            [sparkboard.ui.icons :as icons]
            [sparkboard.ui.radix :as radix]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [re-db.api :as db]
            [re-db.reactive :as r]
            [promesa.core :as p]
            [sparkboard.query :as q]))

;; TODO

;; SETTINGS (boards)
;; - re-order fields
;; - confirm before removing a field (radix alert?)
;; - re-order options
;; - add a new field
;;   - (def blanks {<type> <template>})
;;   - an entity/add-multi! endpoint for adding a new cardinality/many entity
;;     (in this case, a new :field which is pointed to by :board/member-fields or :board/project-fields)
;;   - entity/remove-multi! endpoint; use db/isComponent to determine whether the target is retracted?
;; - remove a field
;;   - an entity/retract-multi! endpoint

;; ENTITIES (members/projects)
;; - displaying the value of a field
;; - editing a field's value
;; - CARDS: showing fields on cards
;; - REGISTRATION: showing fields during membership creation



(sch/register!
  {:image/url                   {s- :http/url}

   :field/hint                  {s- :string},
   :field/label                 {s- :string},
   :field/default-value         {s- :string}
   :field/options               {s- (? [:sequential :field/option])},
   :field/option                {s- [:map {:closed true}
                                     (? :field-option/color)
                                     (? :field-option/value)
                                     :field-option/label]}
   :field/order                 {s- :int},
   :field/required?             {s- :boolean},
   :field/show-as-filter?       {:doc "Use this field as a filtering option"
                                 s-   :boolean},
   :field/show-at-registration? {:doc "Ask for this field when creating a new entity"
                                 s-   :boolean},
   :field/show-on-card?         {:doc "Show this field on the entity when viewed as a card"
                                 s-   :boolean},
   :field/type                  {s- [:enum
                                     :field.type/images
                                     :field.type/video
                                     :field.type/select
                                     :field.type/link-list
                                     :field.type/prose]}

   :link-list/link              {:todo "Tighten validation after cleaning up db"
                                 s-    [:map {:closed true}
                                        (? [:text :string])
                                        [:url :string]]}
   :field-option/color          {s- :html/color},
   :field-option/default        {s- :string},
   :field-option/label          {s- :string},
   :field-option/value          {s- :string},
   :video/url                   {s- :string}
   :video/entry                 {s- [:map {:closed true}
                                     :video/url]}
   :images/assets               (sch/ref :many :asset/as-map)
   :images/order                {s- [:sequential :entity/id]}
   :link-list/links             {s- [:sequential :link-list/link]}
   :select/value                {s- :string}
   :field-entry/as-map          {s- [:map {:closed true}
                                     (? :images/assets)
                                     (? :images/order)
                                     (? :video/url)
                                     (? :select/value)
                                     (? :link-list/links)
                                     (? :prose/format)
                                     (? :prose/string)]
                                 #_(let [common-fields [:entity/id
                                                        :entity/kind
                                                        :field-entry/type]]
                                     `[:multi {:dispatch :field-entry/type}
                                       [:field.type/images
                                        [:map {:closed true}
                                         ~@common-fields
                                         :images/assets
                                         :images/order]]

                                       [:field.type/video
                                        [:map {:closed true}
                                         ~@common-fields
                                         :video/type
                                         :video/value]]

                                       [:field.type/select
                                        [:map {:closed true}
                                         ~@common-fields
                                         :select/value]]

                                       [:field.type/link-list
                                        [:map {:closed true}
                                         ~@common-fields
                                         :link-list/links]]

                                       [:field.type/prose
                                        [:map {:closed true}
                                         ~@common-fields
                                         :prose/format
                                         :prose/string]]])}
   :field/published?            {s- :boolean}
   :field/as-map                {:doc  "Description of a field."
                                 :todo ["Field specs should be definable at a global, org or board level."
                                        "Orgs/boards should be able to override/add field.spec options."
                                        "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                                 s-    [:map {:closed true}
                                        :entity/id
                                        :entity/kind
                                        :field/order
                                        :field/type
                                        (? :field/published?)
                                        (? :field/hint)
                                        (? :field/label)
                                        (? :field/options)
                                        (? :field/required?)
                                        (? :field/show-as-filter?)
                                        (? :field/show-at-registration?)
                                        (? :field/show-on-card?)]}})

(def field-keys [:field/hint
                 :entity/id
                 :field/label
                 :field/default-value
                 {:field/options [:field-option/color
                                  :field-option/value
                                  :field-option/label]}
                 :field/order
                 :field/required?
                 :field/show-as-filter?
                 :field/show-at-registration?
                 :field/show-on-card?
                 :field/type])

(def field-types {:field.type/prose     {:icon  icons/text
                                         :label (tr :tr/text)}
                  :field.type/select    {:icon  icons/dropdown-menu
                                         :label (tr :tr/menu)}
                  :field.type/video     {:icon  icons/video
                                         :label (tr :tr/video)}
                  :field.type/link-list {:icon  icons/link-2
                                         :label (tr :tr/links)}
                  :field.type/images    {:icon  icons/photo
                                         :label (tr :tr/image)}
                  })

(ui/defview show-select [?field {:field/keys [label options]} entry]
  [:div.flex-v.gap-2
   [ui/input-label label]
   [radix/select-menu {:value      (:select/value @?field)
                       :id         (str (:entity/id entry))
                       :read-only? (:can-edit? ?field)
                       :options    (->> options
                                        (map (fn [{:field-option/keys [label value color]}]
                                               {:text  label
                                                :value value}))
                                        doall)}]])

(defmulti entry-value (fn [field entry] (:field/type field)))

(defmethod entry-value nil [_ _] nil)

(defmethod entry-value :field.type/images [_field entry]
  (when (seq (:images/order entry))
    (select-keys entry [:images/assets :images/order])))

(defmethod entry-value :field.type/video [_field entry]
  (when-let [value (u/guard (:video/url entry) (complement str/blank?))]
    {:video/url value}))

(defmethod entry-value :field.type/select [_field entry]
  (when-let [value (u/guard (:select/value entry) (complement str/blank?))]
    {:select/value value}))

(defmethod entry-value :field.type/link-list [_field entry]
  (when-let [value (u/guard (:link-list/links entry) seq)]
    {:link-list/links value}))

(defmethod entry-value :field.type/prose [_field entry]
  (when-let [value (u/guard (:prose/string entry) (complement str/blank?))]
    {:prose/string value
     :prose/format (:prose/format entry)}))

(q/defx save-entry! [_ parent-id field-id entry]
  {:pre [(uuid? parent-id)
         (uuid? field-id)]}
  (let [field (db/entity (sch/wrap-id field-id))
        parent (db/entity (sch/wrap-id parent-id))
        entries (assoc (get parent :entity/field-entries) field-id entry)]
    (prn :entries entries)
    (validate/assert (db/touch field) :field/as-map)
    (validate/assert entry :field-entry/as-map)
    (db/transact! [[:db/add (:db/id parent) :entity/field-entries entries]])
    {:txs [{:entity/id            (:entity/id parent)
            :entity/field-entries entries}]}))

(ui/defview show-entry
  {:key (comp :entity/id :field)}
  [{:keys [parent field entry can-edit?]}]
  (let [value  (entry-value field entry)
        ?field (h/use-memo #(forms/field :init (entry-value field entry) :label (:field/label field)))
        props  {:label     (:label field)
                :can-edit? can-edit?
                :on-save  (partial save-entry! nil (:entity/id parent) (:entity/id field))}]
    (case (:field/type field)
      :field.type/video [ui/video-field ?field props]
      :field.type/select [ui/select-field ?field (merge props
                                                        {:wrap            (fn [x] {:select/value x})
                                                         :unwrap          :select/value
                                                         :persisted-value value
                                                         :options         (:field/options field)})]
      :field.type/link-list [ui/pprinted value props]
      :field.type/images [ui/images-field ?field props]
      :field.type/prose [ui/prose-field ?field props]
      (str "no match" field))))

(defonce !alert (r/atom nil))

(defn blank? [color]
  (or (empty? color) (= "#ffffff" color) (= "rgb(255, 255, 255)" color)))


(defn element-center-y [el]
  #?(:cljs
     (j/let [^js {:keys [y height]} (j/call el :getBoundingClientRect)]
       (+ y (/ height 2)))))

(defn orderable-props
  [{:keys [group-id
           id
           on-move
           !should-drag?]}]
  #?(:cljs
     (let [transfer-data (fn [e data]
                           (j/call-in e [:dataTransfer :setData] (str group-id)
                                      (pr-str data)))

           receive-data  (fn [e]
                           (try
                             (ui/read-string (j/call-in e [:dataTransfer :getData] (str group-id)))
                             (catch js/Error e nil)))

           data-matches? (fn [e]
                           (some #{(str group-id)} (j/get-in e [:dataTransfer :types])))
           [active-drag set-drag!] (h/use-state nil)
           [active-drop set-drop!] (h/use-state nil)
           !should-drag? (h/use-ref false)]
       [{:on-mouse-down #(reset! !should-drag? true)
         :on-mouse-up   #(reset! !should-drag? false)}
        {:draggable     true
         :data-dragging active-drag
         :data-dropping active-drop
         :on-drag-over  (j/fn [^js {:as e :keys [clientY currentTarget]}]
                          (j/call e :preventDefault)
                          (when (data-matches? e)
                            (set-drop! (if (< clientY (element-center-y currentTarget))
                                         :before
                                         :after))))
         :on-drag-leave (fn [^js e]
                          (j/call e :preventDefault)
                          (set-drop! nil))
         :on-drop       (fn [^js e]
                          (.preventDefault e)
                          (set-drop! nil)
                          (when-let [source (receive-data e)]
                            (on-move {:destination id
                                      :source      source
                                      :side        active-drop})))
         :on-drag-end   (fn [^js e]
                          (set-drag! nil))
         :on-drag-start (fn [^js e]
                          (if @!should-drag?
                            (do
                              (set-drag! true)
                              (transfer-data e id))
                            (.preventDefault e)))}
        (when active-drop
          (v/x [:div.absolute.bg-focus-accent
                {:class ["h-[4px] z-[99] inset-x-0 rounded"
                         (case active-drop
                           :before "top-[-2px]"
                           :after "bottom-[-2px]" nil)]}]))])))

(defn re-order [xs source side destination]
  {:post [(= (count %) (count xs))]}
  (let [out (reduce (fn [out x]
                      (if (= x destination)
                        (into out (case side :before [source destination]
                                             :after [destination source]))
                        (conj out x)))
                    []
                    (remove #{source} xs))]
    (when-not (= (count out) (count xs))
      (throw (ex-info "re-order failed, destination not found" {:source source :destination destination})))
    out))

(ui/defview show-option [{:keys [?options save!]} {:as ?option :syms [?label ?value ?color]}]
  (let [[handle-props drag-props indicator]
        (orderable-props {:group-id (goog/getUid ?options)
                          :id       (:sym ?option)
                          :on-move  (fn [{:keys [source side destination]}]
                                      (forms/swap-many! ?options re-order
                                                        (get ?options source)
                                                        side
                                                        (get ?options destination))
                                      (save!))})]
    [:div.flex.gap-2.items-center.group.relative.-ml-6.py-1
     (merge {:key @?value}
            drag-props)
     [:div
      indicator
      [:div.flex.flex-none.items-center.justify-center.icon-gray
       (merge handle-props
              {:class ["w-6 -mr-2"
                       "opacity-0 group-hover:opacity-100"
                       "cursor-drag"]})
       [icons/drag-dots]]]
     [ui/text-field ?label {:on-save       save!
                            :wrapper-class "flex-auto"
                            :class         "rounded-sm relative focus:z-2"
                            :style         {:background-color @?color
                                            :color            (ui/contrasting-text-color @?color)}}]
     [:div.relative.w-10.focus-within-ring.rounded.overflow-hidden.self-stretch
      [ui/color-field ?color {:on-save save!
                              :style   {:top -10 :left -10 :width 100 :height 100 :position "absolute"}}]]
     [radix/dropdown-menu {:id       :field-option
                           :trigger  [:button.p-1.relative.icon-gray.cursor-default
                                      [icons/ellipsis-horizontal "w-4 h-4"]]
                           :children [[{:on-select (fn [_]
                                                     (radix/open-alert! !alert
                                                                        {:body   [:div.text-center.flex-v.gap-3
                                                                                  [icons/trash "mx-auto w-8 h-8 text-red-600"]
                                                                                  [:div.text-2xl (tr :tr/confirm)]
                                                                                  [:div (tr :tr/cannot-be-undone)]]
                                                                         :cancel [:div.btn.thin {:on-click #(radix/close-alert! !alert)}
                                                                                  (tr :tr/cancel)]
                                                                         :action [:div.btn.destruct
                                                                                  {:on-click (fn [_]
                                                                                               (forms/remove-many! ?option)
                                                                                               (p/do (save!)
                                                                                                     (radix/close-alert! !alert)))}
                                                                                  (tr :tr/delete)]}))}
                                       "Remove"]]}]]))

(ui/defview options-field [?field {:keys [on-save
                                          persisted-value]}]
  (let [memo-by [(str (map :field-option/value persisted-value))]
        {:syms [?options]} (h/use-memo (fn []
                                         (forms/form (?options :many {:field-option/label ?label
                                                                      :field-option/value ?value
                                                                      :field-option/color ?color}
                                                               :init (mapv #(set/rename-keys % '{:field-option/label ?label
                                                                                                 :field-option/value ?value
                                                                                                 :field-option/color ?color}) @?field))))
                                       memo-by)]

    (let [save! (fn [& _] (on-save (mapv u/prune @?options)))]
      [:div.col-span-2.flex-v.gap-3
       [ui/input-label (tr :tr/options)]
       (when (:loading? ?options)
         [:div.loading-bar.absolute.h-1.top-0.left-0.right-0])
       (into [:div.flex-v]
             (map (partial show-option {:?options ?options
                                        :save!    save!}) ?options))
       (let [?new (h/use-memo #(forms/field :init "") memo-by)]
         [:form.flex.gap-2 {:on-submit (fn [^js e]
                                         (.preventDefault e)
                                         (forms/add-many! ?options {'?value (str (random-uuid))
                                                                    '?label @?new
                                                                    '?color "#ffffff"})
                                         (forms/try-submit+ ?new (save!)))}
          [ui/text-field ?new {:placeholder "Option label" :wrapper-class "flex-auto"}]
          [:div.btn.bg-white.px-3.py-1.shadow "Add Option"]])
       #_[ui/pprinted @?options]])))

(ui/defview field-editor-detail [attribute field]
  [:div.bg-gray-100.gap-3.grid.grid-cols-2.pl-12.pr-7.pt-1.pb-6

   [:div.col-span-2.flex-v.gap-3
    (entity/use-persisted field :field/label ui/text-field {:class      "bg-white text-sm"
                                                            :multi-line true})
    (entity/use-persisted field :field/hint ui/text-field {:class       "bg-white text-sm"
                                                           :multi-line  true
                                                           :placeholder "Further instructions"})]

   (when (= :field.type/select (:field/type field))
     (entity/use-persisted field :field/options options-field))
   #_[:div.flex.items-center.gap-2.col-span-2
      [:span.font-semibold.text-xs.uppercase (:label (field-types (:field/type field)))]]
   [:div.contents.labels-normal
    (entity/use-persisted field :field/required? ui/checkbox-field)
    (entity/use-persisted field :field/show-as-filter? ui/checkbox-field)
    (when (= attribute :board/member-fields)
      (entity/use-persisted field :field/show-at-registration? ui/checkbox-field))
    (entity/use-persisted field :field/show-on-card? ui/checkbox-field)]])

(ui/defview field-editor
  {:key (comp :entity/id :field)}
  [{:keys [attribute order-by default-expanded? field]}]
  (let [field-type (field-types (:field/type field))
        [expanded? expand!] (h/use-state default-expanded?)
        [handle-props drag-props indicator] (orderable-props {:group-id attribute
                                                              :id       (sch/wrap-id (:entity/id field))
                                                              :on-move
                                                              (fn [{:as args :keys [source destination side]}]
                                                                (p/-> (entity/order-ref! {:attribute   attribute
                                                                                          :order-by    order-by
                                                                                          :source      source
                                                                                          :side        side
                                                                                          :destination destination})
                                                                      db/transact!))})]
    [:div.flex-v.relative.border-b
     ;; label row
     [:div.flex.gap-3.p-3.items-stretch.hover:bg-gray-100.relative.group.cursor-default.relative
      (v/merge-props
        drag-props
        {:class    (when expanded? "bg-gray-100")
         :on-click #(expand! not)})
      indicator
      ;; expandable label group
      [:div.w-5.-ml-8.flex.items-center.justify-center.hover:cursor-grab.active:cursor-grabbing.opacity-0.group-hover:opacity-50
       handle-props
       [icons/drag-dots "icon-sm"]]
      [(:icon field-type) "cursor-drag icon-lg text-gray-700 self-center"]
      [:div.flex-auto.flex-v.gap-2
       (or (-> (:field/label field)
               (u/guard (complement str/blank?)))
           [:span.text-gray-500 (tr :tr/untitled)])]

      ;; expansion arrow
      [:div.flex.items-center.group-hover:text-black.text-gray-500.pl-2
       [icons/chevron-down:mini (str "w-4" (when expanded? " rotate-180"))]]]
     (when expanded?
       (field-editor-detail attribute field))]))

(q/defx add-field
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} e a field]
  (validate/assert-can-edit! e account-id)
  (let [e               (sch/wrap-id e)
        existing-fields (->> (a (db/entity e))
                             (sort-by :field/order))
        field           (-> field
                            (assoc :field/order (or (:field/order (last existing-fields))
                                                    0)
                                   :entity/kind :field
                                   :entity/id (dl/new-uuid :field)))]
    (validate/assert field :field/as-map)
    (db/transact! [(assoc field :db/id -1)
                   [:db/add e a -1]])
    {:entity/id (:entity/id field)}))

(ui/defview fields-editor [entity attribute]
  (let [label (:label (forms/global-meta attribute))
        !new-field       (h/use-state nil)
        !autofocus-ref   (ui/use-autofocus-ref)
        fields           (->> (get entity attribute)
                              (sort-by :field/order))
        !field-count     (h/use-ref (count fields))
        default-expanded (let [last-id (:entity/id (last fields))
                               id (when (= (count fields) (inc @!field-count))
                                    last-id)]
                           (reset! !field-count (count fields))
                           id)]
    [ui/input-wrapper {:class "labels-semibold"}
     (when-let [label (or label (tr attribute))]
       [ui/input-label {:class "flex items-center"}
        label
        [:div.flex.ml-auto.items-center
         (when-not @!new-field
           (radix/dropdown-menu {:id       :add-field
                                 :trigger  [:div.text-sm.text-gray-500.font-normal.hover:underline.cursor-pointer.place-self-center
                                            "Add Field"]
                                 :children (for [[type {:keys [icon label]}] field-types]
                                             [{:on-select #(reset! !new-field
                                                                   (forms/form {:field/type       ?type
                                                                                :field/label      ?label
                                                                                :field/published? (case type
                                                                                                    :field.type/select false
                                                                                                    true)}
                                                                               :init {'?type type}
                                                                               :required [?label]))}
                                              [:div.flex.gap-4.items-center.cursor-default [icon "text-gray-600"] label]])}))]])
     [:div.flex-v.border.rounded.labels-sm
      (->> fields
           (map (fn [field]
                  (field-editor {:attribute         attribute
                                 :order-by          :field/order
                                 :default-expanded? (= default-expanded (:entity/id field))
                                 :field             field})))
           doall)]
     (when-let [{:as   !form
               :syms [?type ?label]} @!new-field]
       [:div
        [:form.flex.gap-2.items-start.relative
         {:on-submit (ui/prevent-default
                       (fn [e]
                         (forms/try-submit+ !form
                           (p/do
                             (add-field nil (:entity/id entity) attribute @!form)
                             (reset! !new-field nil)))))}
         [:div.flex.items-center.justify-center.absolute.icon-light-gray.h-10.w-7.-right-7
          {:on-click #(reset! !new-field nil)}
          [icons/close "w-5 h-5 "]]
         [:div.h-10.flex.items-center [(:icon (field-types @?type)) "icon-lg text-gray-700  mx-2"]]
         [ui/text-field ?label {:label         false
                                :ref           !autofocus-ref
                                :placeholder   (:label ?label)
                                :wrapper-class "flex-auto"}]
         [:button.btn.btn-white.h-10 {:type "submit"}
          (tr :tr/add)]]
        [:div.pl-12.py-2 (ui/show-field-messages !form)]])
     [radix/alert !alert]]))