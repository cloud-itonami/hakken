(ns hakken.registry
  (:require [hakken.pipeline :as pipeline]))

(def task-types
  #{"health"
    "discovery"
    "phase_promotion"
    "trend_scan"
    "supplier_search"
    "quality_eval"
    "phase_router"})

(defn dispatch [task-type payload]
  (case task-type
    "health" {:status "ok" :profile "hakken"}
    "discovery" (pipeline/discovery payload)
    "phase_promotion" (pipeline/phase-promotion payload)
    "trend_scan" (pipeline/trend-scan (pipeline/empty-state payload))
    "supplier_search" (pipeline/supplier-search (pipeline/empty-state payload))
    "quality_eval" (pipeline/quality-eval (pipeline/empty-state payload))
    "phase_router" (pipeline/phase-router (pipeline/empty-state payload))
    {:error "unknown_task"
     :task_type task-type
     :known_tasks (vec (sort task-types))}))
