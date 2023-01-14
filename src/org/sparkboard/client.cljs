(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [clojure.edn :as edn]
            [org.sparkboard.client.auth :as auth.client]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.routes :refer [routes]]
            [org.sparkboard.client.slack :as slack.client]
            [org.sparkboard.websockets :as ws]
            [re-db.integrations.reagent] ;; extends `ratom` reactivity
            [re-db.sync.transit :as re-db.transit]
            [reagent.dom]
            [reitit.core :as reitit]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]
            [yawn.hooks :as hooks]
            [yawn.root :as root]
            [yawn.view :as v]))

(defonce !current-match (atom nil))

(def router (rf/router routes {}))

(defn path [k & [params]]
  (-> router
      (reitit/match-by-name k params)
      (reitit/match->path)))

(v/defview home [] "Nothing to see here, folks.")

;;; XXX based on sync_values
(def default-options
  {:pack re-db.transit/pack
   :unpack re-db.transit/unpack
   :path "/ws"})

(v/defview playground []
  [:div
   [:a {:href "/skeleton"} "skeleton"]
   [:p (str "entity-1: " (ws/use-query :entity-1))]
   [:p (str "sb/orgs: " (ws/use-query :sb/orgs))]
   [:button.p-2.rounded.bg-blue-100
    {:on-click #(ws/send [:conj!])}
    "List, grow!"]])

(v/defview skeleton []
  [:div
   (into [:ul]
         (map (fn [org-obj]
                [:li
                 [:a {:href (str "/skeleton/org/" (:org/id org-obj))} (:org/title org-obj)]]))
         (:value (ws/use-query :sb/orgs)))])

(def handlers {:home home
               :playground playground
               :skeleton skeleton
               :slack/invite-offer slack.client/invite-offer
               :slack/link-complete slack.client/link-complete
               :auth-test auth.client/auth-header})

(v/defview root []
  (let [{:keys [view] :as m} (hooks/use-atom !current-match)]
    (if view
      [view m]
      "not-found")))

(defonce !react-root (atom nil))

(defn render []
  (root/render @!react-root (root)))

(defn ^:dev/after-load start-router []
  (rfe/start!
   router
   (fn [m]
     (reset! !current-match (assoc m :view (handlers (:name (:data m))))))
   ;; set to false to enable HistoryAPI
   {:use-fragment false}))

(defn init []
  (firebase/init)

  (reset! !react-root (root/create :web (root)))

  (start-router)
  (render))
