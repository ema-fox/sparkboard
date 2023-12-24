(ns sb.app.form.ui
  (:require [applied-science.js-interop :as j]
            [inside-out.forms]
            [inside-out.forms :as io]
            [sb.app.entity.data :as entity.data]
            [sb.app.views.ui :as ui]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.util :as u]
            [yawn.view :as v]))


(defn field-id [?field]
  #?(:cljs
     (str "field-" (goog/getUid ?field))
     :clj
     (str "field-" (:sym ?field))))

(defn maybe-save-field [?field props value]
  (if-let [on-save (and (not= value (entity.data/persisted-value ?field))
                        (:on-save props))]
    (do
      (prn :saving value)
      (reset! ?field value)
      (io/try-submit+ ?field
        (on-save)))
    (prn :not-saving value)))

(defn pass-props [props] (dissoc props
                                 :multi-line :postfix :wrapper-class
                                 :on-save :on-change-value
                                 :wrap :unwrap
                                 :inline?
                                 :can-edit?
                                 :label))

(defn get-label [label ?field]
  (u/some-or label (:label ?field)))

(defn show-label [?field & [label]]
  (when-let [label (get-label label ?field)]
    [:label.field-label {:for (field-id ?field)} label]))

(defn ?field-props [?field
                    get-value
                    {:as   props
                     :keys [wrap
                            unwrap
                            on-change-value
                            on-change
                            save-on-change?]
                     :or   {wrap identity unwrap identity}}]
  {:id              (field-id ?field)
   :value           (unwrap @?field)
   :on-change       (fn [e]
                      (let [new-value (wrap (get-value e))]
                        (reset! ?field new-value)
                        (when on-change-value
                          (pass-props (on-change-value new-value)))
                        (when on-change
                          (on-change e))
                        (when save-on-change?
                          (maybe-save-field ?field props new-value))))
   :on-blur         (fn [e]
                      (maybe-save-field ?field props (wrap (get-value e)))
                      ((io/blur-handler ?field) e))
   :on-focus        (io/focus-handler ?field)})

(def email-validator (fn [v _]
                       (when v
                         (when-not (re-find #"^[^@]+@[^@]+$" v)
                           (t :tr/invalid-email)))))

(def form-classes "flex-v gap-4 p-6 max-w-lg mx-auto bg-back relative text-sm")

(ui/defview view-message [{:keys [type content]}]
  (case type
    :in-progress (ui/loading:spinner " h-4 w-4 text-blue-600 ml-2")
    [:div
     {:style (case type
               (:error :invalid) {:color            color/invalid-text-color
                                  :background-color color/invalid-bg-color}
               nil)}
     content]))

(defn show-field-messages [?field]
  (when-let [messages (seq (io/visible-messages ?field))]
    (v/x (into [:div.gap-3.text-sm] (map view-message messages)))))

(ui/defview submit-form [?form label]
  [:<>
   (show-field-messages ?form)
   [:button.btn.btn-primary
    {:type     "submit"
     :disabled (not (io/submittable? ?form))}
    label]])

(ui/defview filter-field [?field attrs]
  (let [loading? (or (:loading? ?field) (:loading? attrs))]
    [:div.flex.relative.items-stretch.flex-auto
     [:input.pr-9.border.border-gray-300.w-full.rounded-lg.p-3
      (v/props (?field-props ?field (j/get-in [:target :value]) {:unwrap #(or % "")})
               {:class       ["outline-none focus-visible:outline-4 outline-offset-0 focus-visible:outline-gray-200"]
                :placeholder "Search..."
                :on-key-down #(when (= "Escape" (.-key ^js %))
                                (reset! ?field nil))})]
     [:div.absolute.top-0.right-0.bottom-0.flex.items-center.pr-3
      {:class "text-txt/40"}
      (cond loading? (icons/loading "w-4 h-4 rotate-3s")
            (seq @?field) [:div.contents.cursor-pointer
                           {:on-click #(reset! ?field nil)}
                           (icons/close "w-5 h-5")]
            :else (icons/search "w-6 h-6"))]]))