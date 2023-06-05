(ns sparkboard.entities.org
  (:require [malli.util :as mu]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl]
            [sparkboard.entities.domain :as domain]
            [sparkboard.entities.entity :as entity]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]))

(defn delete!
  "Mutation fn. Retracts organization by given org-id."
  [_req {:keys [org]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:entity/id org]]])
  {:body ""})


(defn edit-query [params]
  ;; all the settings that can be changed
  (db/pull '[*
             {:image/logo [:asset/id]}
             {:image/background [:asset/id]}
             {:entity/domain [:domain/name]}] 
           [:entity/id (:org params)]))


(defn list-query [_]
  (->> (db/where [[:entity/kind :org]])
       (mapv (re-db.api/pull '[*
                               {:image/logo [:asset/id]}
                               {:image/background [:asset/id]}]))))

(defn read-query [params]
  (db/pull `[~@entity/fields
             {:board/_org ~entity/fields}]
           [:entity/id (:org params)]))

(defn search-query [{:as         params 
                     :keys       [org]
                     {:keys [q]} :query-params}]
  {:q        q
   :boards   (dl/q '[:find [(pull ?board [:entity/id
                                          :entity/title
                                          :entity/kind
                                          :image/logo
                                          :image/backgrouond
                                          {:entity/domain [:domain/name]}]) ...]
                     :in $ ?terms ?org
                     :where
                     [?board :board/org ?org]
                     [(fulltext $ ?terms {:top 100}) [[?board ?a ?v]]]]
                   q
                   [:entity/id org])
   :projects (dl/q '[:find [(pull ?project [:entity/id
                                            :entity/title
                                            :entity/kind
                                            :entity/description
                                            :image/logo
                                            :image/backgrouond
                                            {:project/board [:entity/id]}]) ...]
                     :in $ ?terms ?org
                     :where
                     [?board :board/org ?org]
                     [?project :project/board ?board]
                     [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]]
                   q
                   [:entity/id org])})

(defn conform [org]
  (-> org
      (domain/conform-and-validate)
      (validate/assert (-> (mu/optional-keys :org/as-map)
                           (mu/assoc :entity/domain (mu/optional-keys :domain/as-map))))))

(defn edit! [{:keys [account]} params org]
  (let [org (conform (assoc org :entity/id (:org params)))]
    (db/transact! [org])
    {:body org}))

(defn new!
  [{:keys [account]} _ org]
  (let [org (-> (dl/new-entity org :org :by (:db/id account))
                conform)
        member (-> {:member/entity  org
                    :member/account (:db/id account)
                    :member/roles   #{:role/owner}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    {:body org}))