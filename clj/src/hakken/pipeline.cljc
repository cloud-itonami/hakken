(ns hakken.pipeline
  (:require [clojure.string :as str]))

(def grade-order {"S" 5 "A" 4 "B" 3 "C" 2 "D" 1})
(def heavy-kg 5.0)
(def min-margin-drop 0.30)
(def min-margin-import 0.60)
(def min-margin-oem 0.60)
(def min-rating-drop 4.0)
(def min-rating-import 4.5)
(def min-grade-drop "B")

(defn number-or [x default]
  (cond
    (number? x) (double x)
    (string? x) (try
                  #?(:clj (Double/parseDouble x)
                     :cljs (js/parseFloat x))
                  (catch #?(:clj Exception :cljs :default) _ default))
    :else default))

(defn int-or [x default]
  (int (Math/round (number-or x default))))

(defn empty-state [payload]
  (merge {:category "pillow"
          :branded_products []
          :oem_candidates []
          :kotoba_cids []
          :review_scores {}
          :approved_skus []
          :registered_okaimono_ids []
          :announcements []
          :operator_notifications []
          :promoted []
          :errors []}
         payload))

(def stub-branded
  {"pillow" [{:name "Brain Sleep Pillow"
              :brand "Brain Sleep"
              :category "pillow"
              :price_jpy 33000
              :url "https://example.invalid/brain-sleep-pillow"
              :material "polyethylene-fiber"}]
   "mattress" [{:name "Brain Sleep Mattress"
                :brand "Brain Sleep"
                :category "mattress"
                :price_jpy 88000
                :url "https://example.invalid/brain-sleep-mattress"
                :material "polyethylene-fiber"}]})

(def stub-candidates
  {"pillow" [{:name "3D Air Fiber Pillow Washable PE"
              :platform "aliexpress"
              :item_id "1005009071063808"
              :url "https://www.aliexpress.com/item/1005009071063808.html"
              :price_jpy 2500
              :weight_kg 0.5
              :rating 4.7
              :review_count 312
              :material "polyethylene-fiber"
              :thickness_cm nil
              :washable true
              :lead_days 18
              :min_order 1
              :supplier_country "CN"
              :equivalent_of "Brain Sleep Pillow"}]
   "mattress" [{:name "3D Air Fiber Mattress 8cm Washable"
                :platform "aliexpress"
                :item_id "1005007792087113"
                :url "https://www.aliexpress.com/item/1005007792087113.html"
                :price_jpy 12500
                :weight_kg 8.5
                :rating 4.6
                :review_count 189
                :material "polyethylene-fiber"
                :thickness_cm 8
                :washable true
                :lead_days 21
                :min_order 1
                :supplier_country "CN"
                :equivalent_of "Brain Sleep Mattress"}]})

(defn trend-scan [state]
  (let [category (:category state)
        existing (:branded_products state)]
    (assoc state :branded_products (vec (or (seq existing)
                                            (get stub-branded category []))))))

(defn branded-entity [product]
  {:id (str "product:" (:brand product) ":" (:name product))
   :type "BrandedProduct"
   :labelJa (:name product)
   :claims [{:pred "brand" :value (:brand product)}
            {:pred "category" :value (:category product)}
            {:pred "priceJpy" :value (str (:price_jpy product))}
            {:pred "url" :value (:url product)}]})

(defn gap-analysis [state]
  (let [entities (mapv branded-entity (:branded_products state))]
    (assoc state
           :kg_entities entities
           :kotoba_cids (vec (concat (:kotoba_cids state)
                                     (map #(str "local-cid:" (:id %)) entities))))))

(defn supplier-search [state]
  (let [category (:category state)
        existing (:oem_candidates state)]
    (assoc state :oem_candidates (vec (or (seq existing)
                                          (get stub-candidates category []))))))

(defn estimate-score [candidate]
  (let [raw (number-or (:rating candidate) 0.0)
        score (min (int (* raw 20)) 100)
        grade (cond
                (>= score 90) "S"
                (>= score 75) "A"
                (>= score 60) "B"
                :else "C")]
    {:item_id (:item_id candidate)
     :grade grade
     :score score
     :quality (/ raw 5.0)
     :usability (/ raw 5.0)
     :cost_performance 0.8
     :satisfaction (/ raw 5.0)
     :sustainability 0.7}))

(defn quality-eval [state]
  (let [provided (:review_scores state)
        score-pair (fn [candidate]
                     (let [id (:item_id candidate)]
                       [id (or (get provided id)
                               (estimate-score candidate))]))
        scores (into {} (map score-pair (:oem_candidates state)))]
    (assoc state :review_scores scores)))

(defn margin [branded-price oem-price]
  (if (pos? (number-or branded-price 0.0))
    (- 1.0 (/ (number-or oem-price 0.0) (number-or branded-price 1.0)))
    0.0))

(defn grade-ok? [grade min-grade]
  (>= (get grade-order grade 0) (get grade-order min-grade 0)))

(defn target-price [oem-price phase]
  (let [multiplier (case phase
                     "dropship" 2.5
                     "import" 2.8
                     "oem" 3.5
                     2.5)
        raw (int (* (number-or oem-price 0.0) multiplier))]
    (+ (* (quot raw 1000) 1000) 800)))

(defn phase-router [state]
  (let [branded-map (into {} (map (juxt :name identity) (:branded_products state)))
        scores (:review_scores state)
        approved (->> (:oem_candidates state)
                      (keep
                       (fn [candidate]
                         (let [score (get scores (:item_id candidate))
                               branded (get branded-map (:equivalent_of candidate))]
                           (when (and score
                                      branded
                                      (grade-ok? (:grade score) min-grade-drop))
                             (let [m (margin (:price_jpy branded) (:price_jpy candidate))
                                   rating (number-or (:rating candidate) 0.0)
                                   weight (number-or (:weight_kg candidate) 0.0)
                                   phase (cond
                                           (and (> weight heavy-kg)
                                                (>= m min-margin-import)
                                                (>= rating min-rating-import)) "import"
                                           (and (>= m min-margin-oem)
                                                (>= rating min-rating-import)) "oem"
                                           (and (>= m min-margin-drop)
                                                (>= rating min-rating-drop)) "dropship")]
                               (when phase
                                 {:oem_candidate candidate
                                  :branded_product branded
                                  :margin m
                                  :phase phase
                                  :review_score score
                                  :sell_price_jpy (target-price (:price_jpy candidate) phase)})))))))]
    (assoc state :approved_skus (vec approved))))

(defn route-by-phase [state]
  (or (:phase (first (:approved_skus state))) "end"))

(defn register-approved [state]
  (let [ids (mapv (fn [sku]
                    (str "okaimono:" (-> sku :oem_candidate :item_id)))
                  (:approved_skus state))]
    (assoc state :registered_okaimono_ids ids)))

(defn order-notifications [state]
  (let [notes (mapv
               (fn [sku]
                 (let [candidate (:oem_candidate sku)]
                   (case (:phase sku)
                     "import" (format "[Ph2 Import Required] %s item_id=%s price_jpy=%s weight_kg=%s"
                                      (:name candidate) (:item_id candidate)
                                      (:price_jpy candidate) (:weight_kg candidate))
                     "oem" (format "[Ph3 OEM Order Required] %s supplier_item=%s margin=%.0f%%"
                                   (:name candidate) (:item_id candidate)
                                   (* 100 (:margin sku)))
                     nil)))
               (:approved_skus state))]
    (assoc state :operator_notifications (vec (remove nil? notes)))))

(def phase-label {"dropship" "お試し価格"
                  "import" "国内在庫あり"
                  "oem" "自社ブランド"})

(defn social-announce [state]
  (let [announcements (mapv
                       (fn [sku]
                         (let [candidate (:oem_candidate sku)
                               score (:review_score sku)]
                           {:category "home"
                            :text (str "【新着 " (get phase-label (:phase sku) "") "】" (:name candidate) "\n"
                                       "ブランド品比 " (int (* 100 (:margin sku))) "%オフ · "
                                       "評価 " (:grade score) "(" (:score score) "点)\n"
                                       "okaimono.gftd.ai で販売中")}))
                       (:approved_skus state))]
    (assoc state :announcements announcements)))

(defn discovery [payload]
  (-> payload
      empty-state
      trend-scan
      gap-analysis
      supplier-search
      quality-eval
      phase-router
      order-notifications
      register-approved
      social-announce
      (assoc :phase "done")))

(defn promote-row [row]
  (let [phase (or (:phase row) (:from row))]
    (cond
      (and (= phase ":phase/dropship")
           (>= (int-or (:dropshipOrders row (:orders row)) 0) 30)
           (< (number-or (:returnRate row (:rr row)) 1.0) 0.05))
      {:sku (:sku row) :from ":phase/dropship" :to ":phase/import"
       :okaimonoId (:okaimonoId row)}

      (and (= phase ":phase/import")
           (>= (int-or (:monthlyGmv row (:gmv row)) 0) 300000)
           (< (number-or (:returnRate row (:rr row)) 1.0) 0.03)
           (>= (number-or (:marginPotential row (:mp row)) 0.0) 0.60))
      {:sku (:sku row) :from ":phase/import" :to ":phase/oem"
       :okaimonoId (:okaimonoId row)})))

(defn phase-promotion [payload]
  (let [rows (or (:rows payload)
                 (concat (:dropship_rows payload) (:import_rows payload)))
        promoted (vec (keep promote-row rows))]
    {:phase "phase_promotion"
     :promoted promoted
     :errors (vec (:errors payload []))}))
