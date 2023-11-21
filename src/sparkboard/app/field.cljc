(ns sparkboard.app.field
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [inside-out.forms :as forms]
            [sparkboard.app.entity :as entity]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.schema :as sch :refer [? s-]]
            [sparkboard.ui :as ui]
            [sparkboard.ui.icons :as icons]
            [sparkboard.ui.radix :as radix]
            [sparkboard.util :as u]
            [yawn.hooks :as h]
            [re-db.api :as db]
            [re-db.reactive :as r]
            [promesa.core :as p]))

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
   :field/id                    sch/unique-uuid
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
   :video/type                  {s- [:enum
                                     :video.type/youtube-id
                                     :video.type/youtube-url
                                     :video.type/vimeo-url]}
   :video/value                 {s- :string}
   :video/entry                 {s- [:map {:closed true}
                                     :video/value
                                     :video/type]}

   :field-entry/field           (sch/ref :one)
   :field-entry/value           {s- [:multi {:dispatch 'first}
                                     [:field.type/images
                                      [:tuple 'any?
                                       [:sequential [:map {:closed true} :image/url]]]]
                                     [:field.type/link-list
                                      [:tuple 'any?
                                       [:map {:closed true}
                                        [:link-list/items
                                         [:sequential :link-list/link]]]]]
                                     [:field.type/select
                                      [:tuple 'any?
                                       [:map {:closed true}
                                        [:select/value :string]]]]
                                     [:field.type/prose
                                      [:tuple 'any?
                                       :prose/as-map]]
                                     [:field.type/video :video/value
                                      [:tuple 'any? :video/entry]]]}
   :field-entry/as-map          {s- [:map {:closed true}
                                     :entity/id
                                     :entity/kind
                                     :field-entry/field
                                     :field-entry/value]}

   :field/as-map                {:doc  "Description of a field."
                                 :todo ["Field specs should be definable at a global, org or board level."
                                        "Orgs/boards should be able to override/add field.spec options."
                                        "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                                 s-    [:map {:closed true}
                                        :field/id
                                        :field/order
                                        :field/type
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

(def field-types {:field.type/video     {:icon  icons/video
                                         :label (tr :tr/video-field)}
                  :field.type/select    {:icon  icons/dropdown-menu
                                         :label (tr :tr/selection-menu)}
                  :field.type/link-list {:icon  icons/link-2
                                         :label (tr :tr/web-links)}
                  :field.type/images    {:icon  icons/photo
                                         :label (tr :tr/image-field)}
                  :field.type/prose     {:icon  icons/text
                                         :label (tr :tr/text-field)}})

(defonce !alert (r/atom nil))

#?(:cljs
   (defn contrasting-text-color [bg-color]
     (if bg-color
       (try (let [[r g b] (if (= \# (first bg-color))
                            (let [bg-color (if (= 4 (count bg-color))
                                             (str bg-color (subs bg-color 1))
                                             bg-color)]
                              [(js/parseInt (subs bg-color 1 3) 16)
                               (js/parseInt (subs bg-color 3 5) 16)
                               (js/parseInt (subs bg-color 5 7) 16)])
                            (re-seq #"\d+" bg-color))
                  luminance (/ (+ (* r 0.299)
                                  (* g 0.587)
                                  (* b 0.114))
                               255)]
              (if (> luminance 0.5)
                "#000000"
                "#ffffff"))
            (catch js/Error e "#000000"))
       "#000000")))

(defn blank? [color]
  (or (empty? color) (= "#ffffff" color) (= "rgb(255, 255, 255)" color)))

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

    (let [save! (fn [& _] (on-save (mapv u/prune @?options)))
          props {:on-save save!}]
      [:div.col-span-2.flex-v.gap-3
       [ui/input-label (tr :tr/options)]
       (when (:loading? ?options)
         [:div.loading-bar.absolute.h-1.top-0.left-0.right-0])
       [:div.flex-v.gap-2.py-1
        (for [{:as ?option :syms [?label ?value ?color]} ?options]
          [:div.flex.gap-2.items-center.group.relative.-ml-6 {:key @?value}
           [:div.flex.flex-none.items-center.justify-center.icon-gray.relative
            {:class ["w-6 -mr-2"
                     "opacity-0 group-hover:opacity-100"
                     "cursor-grab active:cursor-grabbing"]}
            [icons/drag-dots "w-4"]]
           [ui/text-field ?label (assoc props :wrapper-class "flex-auto"
                                              :class "rounded-sm relative focus:z-2"
                                              :style {:background-color @?color
                                                      :color            (contrasting-text-color @?color)})]
           [ui/color-field ?color (assoc props :class ["w-9 h-9"])]
           [radix/dropdown-menu {:id :field-option
                                 :trigger [:div.p-1.relative.icon-gray
                                           [icons/ellipsis-horizontal "w-4 h-4"]]}
            [{:on-select (fn [_]
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
             "Remove"]]])]
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
    (entity/use-persisted field :field/label ui/text-field {:inline? true
                                                            :class "bg-white p-2"
                                                            :multi-line true})
    (entity/use-persisted field :field/hint ui/text-field {:inline? true
                                                           :class "bg-white p-2 text-xs"
                                                           :multi-line true
                                                           :placeholder "Further instructions"})]

   (when (= :field.type/select (:field/type field))
     (entity/use-persisted field :field/options options-field))
   #_[:div.flex.items-center.gap-2.col-span-2
    [:span.font-semibold.text-xs.uppercase (:label (field-types (:field/type field)))]]
   (entity/use-persisted field :field/required? ui/checkbox-field)
   (entity/use-persisted field :field/show-as-filter? ui/checkbox-field)
   (when (= attribute :board/member-fields)
     (entity/use-persisted field :field/show-at-registration? ui/checkbox-field))
   (entity/use-persisted field :field/show-on-card? ui/checkbox-field)])

(ui/defview field-editor
  {:key (fn [attribute field] (:entity/id field))}
  [attribute field]
  (let [field-type (field-types (:field/type field))
        [expanded? expand!] (h/use-state false)]
    [:div.flex-v.relative

     ;; label row
     [:div.flex.gap-3.p-3.items-stretch.hover:bg-gray-100.relative.group.cursor-default
      {:class (when expanded? "bg-gray-100")
       :on-click #(expand! not)}
      ;; expandable label group

      [:div.w-5.-ml-8.flex.items-center.justify-center.hover:cursor-grab.active:cursor-grabbing.opacity-0.group-hover:opacity-50
       {:on-click #(.stopPropagation %)}
       [icons/drag-dots "w-4"]]
      [(:icon field-type) "w-6 h-6 text-gray-700 self-center"]
      [:div.flex-auto.flex-v.gap-2
       (or (-> (:field/label field)
               (u/guard (complement str/blank?)))
           [:span.text-gray-500 (tr :tr/untitled)])]

      ;; expansion arrow
      [:div.flex.items-center.group-hover:text-black.text-gray-500.pl-2
       [icons/chevron-down:mini (str "w-4" (when expanded? " rotate-180"))]]]
     (when expanded?
       (field-editor-detail attribute field))]))

(ui/defview fields-editor [entity attribute]
  (let [?field (forms/field :attribute attribute)]
    [ui/input-wrapper
     (when-let [label (or (:label ?field) (tr attribute))] [ui/input-label label])
     [:div.flex-v.divide-y.border.rounded
      (->> (get entity attribute)
           (sort-by :field/order)
           (map (partial field-editor attribute))
           doall)]
     [:div
      [radix/alert !alert]
      (apply radix/dropdown-menu {:id :add-field
                                  :trigger
                                  [:button.flex.gap-2.btn.btn-light.px-4.py-2.relative
                                   "Add"
                                   [icons/chevron-down "w-4 h-4"]]}
             (for [[type {:keys [icon label]}] field-types]
               [{:on-select #()} [:div.flex.gap-3.items-center.h-8 [icon "w-5 h-5"] label]]))]]))