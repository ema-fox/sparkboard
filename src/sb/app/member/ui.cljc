(ns sb.app.member.ui
  (:require [sb.app.asset.ui :as asset.ui]
            [sb.app.member.data :as data]
            [sb.i18n :refer [t]]
            [sb.app.views.ui :as ui]))

(ui/defview show
  {:route       "/m/:member-id"
   :view/router :router/modal}
  [params]
  (let [{:as          member
         :member/keys [tags
                       ad-hoc-tags
                       account]} (data/show {:member-id (:member-id params)})
        {:keys [:account/display-name
                :image/avatar]} account]
    [:div
     [:h1 display-name]
     ;; avatar
     ;; fields
     (when-let [tags (seq (concat tags ad-hoc-tags))]
       [:section [:h3 (t :tr/tags)]
        (into [:ul]
              (map (fn [{:tag/keys [label background-color]}]
                     [:li {:style (when background-color {:background-color background-color})} label]))
              tags)])
     (when avatar [:img {:src (asset.ui/asset-src avatar :card)}])]))