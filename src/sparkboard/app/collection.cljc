(ns sparkboard.app.collection
  (:require [sparkboard.schema :as sch :refer [s- ?]]))

(sch/register!
  {:collection/boards (sch/ref :many)
   :collection/as-map {s- [:map {:closed true}
                           :entity/id
                           :entity/kind
                           :collection/boards
                           :entity/title
                           (? :entity/domain)
                           (? :image/avatar)
                           (? :image/background)]}})