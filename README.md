# hakken

**hakken（発見）は、ブランド品と OEM 候補を突き合わせて「その候補をどの販売
フェーズで売るか」を決める決定ランタイムである。** 名前が機能を示さないので
最初に名乗る（superproject `CLAUDE.md` の規約: メタファ名の repo は README 冒頭で
名乗る）。`cloud-itonami/hakken` は west project で、`orgs/cloud-itonami/hakken`
に展開される。

実体は **純関数の決定核（`clj/src/hakken/pipeline.cljc`）+ 3 経路の HTTP
サーバ（`server.cljc`）** の 2 つだけ。動く。手順は
[`docs/operator-quickstart.md`](docs/operator-quickstart.md) に、実際に踏んだ
出力ごと置いてある。

## 今日この repo が「する」こと

| task | 入力 | 出力 |
|---|---|---|
| `health` | — | `{"status":"ok","profile":"hakken"}` |
| `discovery` | `{:category "pillow"｜"mattress"}` または明示の商品行 | 8 段を順に通した state（承認 SKU・売価・オペレータ通知・告知文） |
| `phase_promotion` | `{:rows [...]}` | 昇格条件を満たした行だけの `promoted` |
| `trend_scan` / `supplier_search` / `quality_eval` / `phase_router` | 上記の 1 段だけ | 段の出力 state |

HTTP は `GET /health`、`POST /run`、`POST /invoke`（`/run` と同じ）。
未知の task は 404 + `known_tasks`。

## 今日この repo が「しない」こと

**外部呼び出しは 1 本も無い。** `trend_scan` と `supplier_search` は、呼び出し側が
商品行を渡さなければ `pipeline.cljc` に埋め込まれた固定の 1 行を返す
（ブランド側は `https://example.invalid/...`、候補側は AliExpress の item id
2 件）。この repo に `kakaku` / `kaimono-review` / `okaimono` / `tsukuru` /
`kotoba` のクライアントは存在しない。

- `gap_analysis` が積む `kotoba_cids` は `"local-cid:" + entity id` という**文字列**で、
  content address ではない。
- `okaimono_register` が返す `registered_okaimono_ids` は `"okaimono:" + item_id`
  という**文字列**で、登録は起きていない。
- `social_announce` の `announcements` は**文字列**で、投稿はしない。
- `quality_eval` は評点が渡されなければ `rating × 20` を score とし S/A/B/C を付ける
  （5 軸のうち `cost_performance` は定数 0.8、`sustainability` は定数 0.7）。

**`CLAUDE.md` はこの repo の実装ではなく設計文書である。** そこに書かれた
kotoba KG スキーマ・`ai.gftd.apps.kotobase.kg.*` エンドポイント表・SPARQL・
cross-actor 表は、**この repo のコードのどこからも参照されていない**。読むときは
設計意図として読み、実装状況として読まないこと。

## 決定核（値はすべて `pipeline.cljc` の定数）

**`phase_router` は「今どのフェーズか」ではなく「どのフェーズで売り始めるか」を
決める分類器**で、この順に最初に当たった 1 つを採る:

| 順 | 判定 | 条件 |
|---|---|---|
| 1 | `import` | `weight_kg > 5.0` かつ margin ≥ 0.60 かつ rating ≥ 4.5 |
| 2 | `oem` | margin ≥ 0.60 かつ rating ≥ 4.5 |
| 3 | `dropship` | margin ≥ 0.30 かつ rating ≥ 4.0 |

前段で grade < `B` の候補は落ちる。`margin = 1 − (候補価格 / ブランド価格)`、
売価は候補価格 × {dropship 2.5, import 2.8, oem 3.5} を千円丸め + 800。

**軽くて粗利が高い候補は `dropship` を経由せず、いきなり `oem` に入る。**
同梱の stub 枕（0.5 kg・rating 4.7・margin 0.9242）が実際にそうなり、告知文は
【新着 自社ブランド】になる —— まだ 1 個も売っていない SKU に対して。これは
`clj/test/hakken/pipeline_test.cljc` が `(is (= "oem" (:phase sku)))` として
固定している**意図された挙動**である。`CLAUDE.md` の「Ph1 → Ph2 → Ph3」表は
ライフサイクルに読めるので、そこだけ取り違えやすい。

**ライフサイクルの前進は別 task の `phase_promotion`** で、`dropship → import`
（累積注文 ≥ 30 かつ返品率 < 0.05）と `import → oem`（月次 GMV ≥ 300,000 かつ
返品率 < 0.03 かつ margin ≥ 0.60）の 2 本だけを見る。

**この 2 つは語彙が繋がっていない。** `phase_router` は `"import"` を出し、
`phase_promotion` は `":phase/import"` しか受けない。同一の行で phase 文字列だけを
入れ替えると、前者は `promoted: []`、後者は 1 件昇格する（2026-08-09 実測、
quickstart 手順 6）。`discovery` の出力を `phase_promotion` にそのまま流す経路は
この repo には無い。

## ファイル配置

```
clj/                     ← 実装はすべてこの下（root に src/ は無い）
  deps.edn               tools.deps。:test alias が cognitect test-runner
  src/hakken/            pipeline.cljc / registry.cljc / server.cljc
  test/hakken/           6 tests / 21 assertions
  langgraph.edn          profile・entrypoint・task 一覧（EDN datom 1 件）
  Dockerfile
  bb.edn                 clean task。bb は workspace 全体で退役済みなので走らない
wasm/                    18.5 MB の WASI 0.2 component。repo 内のどこからも参照されない
schema.edn / README.md.edn / edn-datomize.bb
CLAUDE.md                設計文書（上記のとおり実装ではない）
docs/                    operator-quickstart.md と adr/
```

`clj/` 配下という配置には、艦隊の計測上の副作用がある —— 成熟度 scan
（`scripts/itonami-maturity-scan.cljs`）は repo root の `src/` と `test/` だけを
数えるので、**この repo は 13 KB の実装と 5 KB のテストを持ちながら
substrate 軸・test 軸とも 0 と測られる。** 詳細と「だから移動する」を採らない
理由は [`docs/adr/0001-hakken-is-a-decision-kernel-without-ingest.md`](docs/adr/0001-hakken-is-a-decision-kernel-without-ingest.md)。

## 隣接 repo との境界

`hakken` が決めるのは**フェーズと売価だけ**である。価格 DB（`kakaku`）・
5 軸レビュー（`kaimono-review`）・販売面（`okaimono`）・製造発注（`tsukuru`）は
別 actor の仕事で、この repo はそれらの結果を**引数として受け取る**形になっている
（受け取らなければ stub で埋める）。接続を書くなら、この repo に HTTP
クライアントを足すのではなく、呼び出し側が行を渡す今の形を保つほうが、決定核が
純関数のままで済む。
