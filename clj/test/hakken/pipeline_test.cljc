(ns hakken.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [hakken.pipeline :as pipeline]
            [hakken.registry :as registry]))

(deftest discovery-pillow-test
  (let [result (registry/dispatch "discovery" {:category "pillow"})
        sku (first (:approved_skus result))]
    (is (= "done" (:phase result)))
    (is (= 1 (count (:branded_products result))))
    (is (= 1 (count (:oem_candidates result))))
    (is (= "oem" (:phase sku)))
    (is (= 8800 (:sell_price_jpy sku)))
    (is (= ["okaimono:1005009071063808"] (:registered_okaimono_ids result)))
    (is (= 1 (count (:announcements result))))))

(deftest discovery-mattress-import-test
  (let [result (registry/dispatch "discovery" {:category "mattress"})
        sku (first (:approved_skus result))]
    (is (= "import" (:phase sku)))
    (is (= 35800 (:sell_price_jpy sku)))
    (is (= 1 (count (:operator_notifications result))))))

(deftest phase-router-filters-grade-test
  (let [state (pipeline/phase-router
               (pipeline/empty-state
                {:branded_products [{:name "Brand A" :price_jpy 10000}]
                 :oem_candidates [{:name "Candidate"
                                   :item_id "c1"
                                   :price_jpy 1000
                                   :weight_kg 1
                                   :rating 4.9
                                   :equivalent_of "Brand A"}]
                 :review_scores {"c1" {:grade "C" :score 55}}}))]
    (is (empty? (:approved_skus state)))
    (is (= "end" (pipeline/route-by-phase state)))))

(deftest phase-promotion-test
  (let [result (registry/dispatch "phase_promotion"
                                  {:rows [{:sku "sku:1"
                                           :phase ":phase/dropship"
                                           :okaimonoId "ok:1"
                                           :dropshipOrders "30"
                                           :returnRate "0.04"}
                                          {:sku "sku:2"
                                           :phase ":phase/import"
                                           :okaimonoId "ok:2"
                                           :monthlyGmv "300000"
                                           :returnRate "0.02"
                                           :marginPotential "0.60"}
                                          {:sku "sku:3"
                                           :phase ":phase/import"
                                           :monthlyGmv "1"
                                           :returnRate "0.10"
                                           :marginPotential "0.1"}]})]
    (is (= "phase_promotion" (:phase result)))
    (is (= [{:sku "sku:1" :from ":phase/dropship" :to ":phase/import" :okaimonoId "ok:1"}
            {:sku "sku:2" :from ":phase/import" :to ":phase/oem" :okaimonoId "ok:2"}]
           (:promoted result)))))

(deftest registry-test
  (is (= {:status "ok" :profile "hakken"}
         (registry/dispatch "health" {})))
  (is (= "unknown_task" (:error (registry/dispatch "missing" {})))))
