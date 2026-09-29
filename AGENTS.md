# ai-gftd-project-hakken — OEM Product Discovery × D2C

**hakken.gftd.ai** (発見) — AI-First OEM Product Discovery pipeline。ブランドプレミアムギャップを自動発見し、3段階 SKU ライフサイクル (Dropship → Import → OEM) で D2C 販売する。

SSoT: `90-docs/adr/2605270000-hakken-oem-product-discovery-bmc-lean.md`

## 一行定義

Hakken ≝ TrendScan × KotobaDatalog[gap] × AliExpressScraper × KaimonoReview[5-axis] × PhaseRouter[drop|import|oem] × Okaimono[D2C]

## アーキテクチャ

```
nanoid:   h4kk3n0x
did:      did:web:hakken.gftd.ai
runtime:  clj/ task runtime (former Python LangGraph scaffold pruned)
storage:  kotoba (EAVT product KG) + RisingWave (MV / analytics)
status:   scaffold (2026-05-27)
```

## SKU ライフサイクル

| Phase | 条件 | 配送 | 粗利 |
|---|---|---|---|
| Ph1 Dropship | margin≥30%, weight≤5kg, rating≥4.0 | 中国直送 14〜21日 | 30〜37% |
| Ph2 Import | 累積注文≥30件 OR 重量物 (margin≥60%) | 国内倉庫 2〜3日 | 60〜66% |
| Ph3 OEM | GMV≥¥300K/月, 返品率<3% | 国内 翌日〜2日 | 70〜80% |

## LangGraph Pipeline

```
trend_scan → gap_analysis → supplier_search → quality_eval
  → phase_router → [okaimono_dropship | import_order | tsukuru_order]
  → okaimono_register → social_announce
```

別 cron: `phase_promotion` — kotoba Datalog Rule2/3 で昇格候補を検出し自動フェーズ更新

## Kotoba KG Schema (kotobase-kg-v1 graph, 2026-05-28)

kotoba-server は `ai.gftd.apps.kotobase.kg.*` の高レベル KG endpoints を提供
(下位の `quad.create` / `graph.query` 直叩きは非推奨)。固定 graph `kotobase-kg-v1` に
書く。**永続化**: `TieredBlockStore<BudgetedMemory, KuboBlockStore>` (IPFS pin
default; `KOTOBA_IPFS=off` で in-memory)。**認証**: 書き込みは `Authorization: Bearer
<JWT>` (CACAO 不要)。

### Entity types

| type | 用途 |
|---|---|
| `BrandedProduct` | ブランド品 (Brain Sleep など) |
| `OemCandidate` | AliExpress/1688 サプライヤー候補 |
| `Sku` | SKU ライフスタイル管理 (phase/dropshipOrders/monthlyGmv) |

### Entity payload (kg.ingest / kg.ingest_batch)

```json
{
  "id":      "sku:hakken:<nanoid>",
  "type":    "Sku",
  "labelJa": "...",
  "claims":  [
    {"pred": "phase",            "value": ":phase/dropship"},
    {"pred": "dropshipOrders",   "value": "12"},
    {"pred": "returnRate",       "value": "0.03"},
    {"pred": "monthlyGmv",       "value": "150000"},
    {"pred": "okaimonoId",       "value": "okaimono-item-xyz"}
  ],
  "relations": [
    {"pred": "equivalentOf", "dstId": "product:brain-sleep:pillow-standard"}
  ]
}
```

### Query (kg.sparql)

```sparql
SELECT ?sku ?okaimonoId WHERE {
  ?sku <kg/claim/phase> ":phase/dropship" .
  ?sku <kg/claim/dropshipOrders> ?orders .
  FILTER (xsd:integer(?orders) >= 30)
}
```

### Endpoint table

| NSID | 用途 | スループット |
|---|---|---|
| `ai.gftd.apps.kotobase.kg.ingest_batch` | bulk ingest (1000 entities/call) | 142× faster than per-entity |
| `ai.gftd.apps.kotobase.kg.ingest` | single entity (upsert by id) | — |
| `ai.gftd.apps.kotoba.graph.sparql` | SELECT/DESCRIBE/CONSTRUCT/ASK | cold path (IPFS) |
| `ai.gftd.apps.kotobase.kg.search` | vector cosine similarity (labelVec) | hot path |
| `ai.gftd.apps.kotobase.kg.entity` | id 直引き | hot path |
| `ai.gftd.apps.kotobase.kg.delete` | entity 削除 | — |

## Cross-Actor

| Actor | 用途 |
|---|---|
| `kakaku.gftd.ai` | ブランド品価格DB (gap_analysis の入力) |
| `kaimono-review.gftd.ai` | 5軸品質スコアリング (quality_eval) |
| `okaimono.gftd.ai` | D2C 販売チャネル (okaimono_register) |
| `tsukuru.gftd.ai` | Ph3 OEM 製造発注 (tsukuru_order) |
| `kotoba` | EAVT product KG ストレージ |

## 初期カテゴリ (2026-Q3)

寝具・ウェルネス (ファイバー枕/トッパー → dropship, マットレス本体 → import)

## ファイル構成

```
clj/
├── deps.edn
├── langgraph.edn
├── src/hakken/
│   ├── pipeline.cljc     # discovery + phase promotion logic
│   ├── registry.cljc     # task dispatch
│   └── server.cljc        # /health /run /invoke
└── test/hakken/

wasm/
└── hakken-phase-promotion.wasm  # preserved generated artifact
```
