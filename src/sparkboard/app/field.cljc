(ns sparkboard.app.field
  (:require [sparkboard.app.entity :as entity]
            [sparkboard.schema :as sch :refer [s- ?]]
            [sparkboard.ui :as ui]
            [sparkboard.ui.icons :as icons]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui.radix :as radix]))

;; TODO
;; views for:
;; - listing the fields (specs) defined for an org/board
;; - adding/removing fields (specs) of an org/board

;; - displaying the value of a field
;; - editing a field's value


(sch/register!
  {:image/url             {s- :http/url}

   :field/hint            {s- :string},
   :field/id              sch/unique-uuid
   :field/label           {s- :string},
   :field/default-value   {s- :string}
   :field/options         {s- (? [:sequential :field/option])},
   :field/option          {s- [:map {:closed true}
                               (? :field-option/color)
                               (? :field-option/value)
                               :field-option/label]}
   :field/order           {s- :int},
   :field/required?       {s- :boolean},
   :field/show-as-filter? {:doc "Use this field as a filtering option"
                           s-   :boolean},
   :field/show-at-create? {:doc "Ask for this field when creating a new entity"
                           s-   :boolean},
   :field/show-on-card?   {:doc "Show this field on the entity when viewed as a card"
                           s-   :boolean},
   :field/type            {s- [:enum
                               :field.type/images
                               :field.type/video
                               :field.type/select
                               :field.type/link-list
                               :field.type/prose]}

   :link-list/link        {:todo "Tighten validation after cleaning up db"
                           s-    [:map {:closed true}
                                  (? [:text :string])
                                  [:url :string]]}
   :field-option/color    {s- :html/color},
   :field-option/default  {s- :string},
   :field-option/label    {s- :string},
   :field-option/value    {s- :string},
   :video/type            {s- [:enum
                               :video.type/youtube-id
                               :video.type/youtube-url
                               :video.type/vimeo-url]}
   :video/value           {s- :string}
   :video/entry           {s- [:map {:closed true}
                               :video/value
                               :video/type]}

   :field-entry/id        sch/unique-uuid
   :field-entry/field     (sch/ref :one)
   :field-entry/value     {s- [:multi {:dispatch 'first}
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
   :field-entry/as-map    {s- [:map {:closed true}
                               :field-entry/id
                               :field-entry/field
                               :field-entry/value]}

   :field/as-map          {:doc  "Description of a field."
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
                                  (? :field/show-at-create?)
                                  (? :field/show-on-card?)]}})

(def icons {:field.type/video     icons/play-circle
            :field.type/select    icons/queue-list:mini
            :field.type/link-list icons/link:mini
            :field.type/images    icons/photo:mini
            :field.type/prose     icons/pencil-square:mini})


(ui/defview field-editor
  {:key :entity/id}
  [{:as field :field/keys [id type hint label]}]
  (let [icon (icons type)]
    [:div.rounded.p-2.gap-2.flex
     [:div.bg-white.p-1.rounded.shadow.cursor-grab.self-start
      [icon "flex-none w-5 h-5"]]
     [:div.font-medium.flex-auto.flex.flex-col label
      [:div.flex.flex-col
       [:div.text-gray-500 (tr type)]
       [:div.text-gray-500 hint]]]
     [icons/ellipsis-horizontal "w-5 h-5"]]))

;; TODO
;; 1. add a new field
;; 2. edit an existing field's common attributes
;;    - :field/label
;;    - :field/hint
;;    - :field/required?
;;    - :field/show-as-filter?
;;    - :field/show-at-create?
;;    - :field/show-on-card?
;; 3. edit an existing field's type-specific attributes
;;    - select: options (:field-option/color,
;;                       :field-option/value,
;;                       :field-option/label)
;; 4. re-order fields
;; 5. remove fields (with confirmation)


(ui/defview fields-editor [entity attribute]
  [:<>
   [:div.flex.flex-col.gap-2.divide-y
    (->> (get entity attribute)
         (sort-by :field/order)
         (map field-editor)
         doall)]
   [:div
    (apply radix/dropdown-menu {:trigger
                                [:div.flex.gap-2.btn.btn-light.px-4.py-2.relative
                                 "Add"
                                 [icons/chevron-down "w-4 h-4"]]}
           (for [[type icon] icons]
             [{:on-select #()} [:div.flex.gap-3.items-center [icon "w-4 h-4"] (tr type)]]))]])