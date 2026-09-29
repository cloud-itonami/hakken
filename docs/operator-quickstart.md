# operator quickstart

**この repo は動く。** 決定核（`clj/src/hakken/pipeline.cljk`）と HTTP サーバを
自分の手元で起動して、フェーズ判定を実際に出させるまでが 9 手順ある。

9 手順とも **2026-08-09 に実際に踏んで、出力をそのまま貼ってある**。必要なのは
`clojure` CLI（= JDK）と `curl` / `python3` / `git` / `gh` だけ。

```bash
REPO=$HOME/github/com-junkawasaki/orgs/cloud-itonami/hakken
ROOT=$HOME/github/com-junkawasaki                       # superproject
```

---

## 手順 0 — checkout が pin と main の両方に一致しているか

west の pin は「manifest に書かれた commit」であって upstream の最新ではない。
先に三点（west.yml / 手元 / GitHub の main）が揃っているか見る。

```bash
grep -A3 'name: hakken$' "$ROOT/manifest/west.yml" | grep revision
git -C "$REPO" rev-parse HEAD
gh api repos/cloud-itonami/hakken/compare/$(git -C "$REPO" rev-parse HEAD)...main \
  --jq '{status, ahead_by, behind_by}'
```

実測:

```
      revision: 798737977da5a0d09dbb741b50c602ade62863fc
798737977da5a0d09dbb741b50c602ade62863fc
{"ahead_by":0,"behind_by":0,"status":"identical"}
```

三点一致。ずれていたら先に `west-pin-advance` skill を読むこと。

## 手順 1 — 前提を確かめる

依存は tools.deps（`clj/deps.edn`）だけで、Node も Python も要らない。

```bash
clojure --version
java -version 2>&1 | head -1
```

実測:

```
Clojure CLI version 1.12.5.1654
openjdk version "24.0.2" 2025-07-15
```

`clojure` が無ければ `brew install clojure/tools/clojure`。初回は Maven 依存
（clojure / jsonista / slf4j / test-runner）を取りに行くので外向き HTTPS が要る。

## 手順 2 — テストを通す（何かを変える前に一度）

**変更前から赤い repo は触らない。** 自分の変更の可否を判定できなくなる。

```bash
cd "$REPO/clj" && kbb -M:test
```

実測:

```
Running tests in #{"test"}

Testing hakken.pipeline-test

Testing hakken.server-test

Ran 6 tests containing 21 assertions.
0 failures, 0 errors.
```

6 tests / 21 assertions。決定核（枕・マットレス・grade 足切り・昇格）と
サーバ（`/health` 200・`/run` 200・未知 task 404）の両方を覆っている。

> ⚠ このワークスペースでは重い build を同時 1 本に制限している。CI や他セッションと
> 並走しうる場所で回すときは
> `node "$ROOT/scripts/resource-guard.mjs" run build -- kbb -M:test` を使う。

## 手順 3 — サーバを起動して `/health` を叩く

```bash
cd "$REPO/clj"
LANGSERVER_PORT=8412 kbb -M -m hakken.server &
sleep 20                      # JVM 起動 + 依存解決。初回はもっとかかる
curl -s http://127.0.0.1:8412/health; echo
```

実測:

```
[main] INFO hakken.server - hakken CLJ server listening #object[java.net.InetSocketAddress 0x29b40b3 /[0:0:0:0:0:0:0:0]:8412]
{"status":"ok","profile":"hakken"}
```

`LANGSERVER_PORT` 未指定なら 8080。`0.0.0.0` に bind するので、共有マシンで
上げっぱなしにしない（手順 8 で必ず落とす）。

## 手順 4 — `discovery`（枕）— **軽くて粗利が高いと `dropship` を飛ばす**

```bash
curl -s -X POST http://127.0.0.1:8412/run -H 'content-type: application/json' \
  -d '{"task_type":"discovery","payload":{"category":"pillow"}}' \
| python3 -c 'import json,sys;d=json.load(sys.stdin);s=d["approved_skus"][0];print(json.dumps({"phase":s["phase"],"margin":round(s["margin"],4),"sell_price_jpy":s["sell_price_jpy"],"grade":s["review_score"]["grade"],"okaimono":d["registered_okaimono_ids"],"announce":d["announcements"][0]["text"]},ensure_ascii=False,indent=2))'
```

実測:

```json
{
  "phase": "oem",
  "margin": 0.9242,
  "sell_price_jpy": 8800,
  "grade": "S",
  "okaimono": [
    "okaimono:1005009071063808"
  ],
  "announce": "【新着 自社ブランド】3D Air Fiber Pillow Washable PE\nブランド品比 92%オフ · 評価 S(94点)\nokaimono.gftd.ai で販売中"
}
```

**読み方**: payload に商品行を渡していないので、`trend_scan` と `supplier_search` は
`pipeline.cljc` の固定 stub（ブランド枕 33,000 円 / 候補 2,500 円・0.5 kg・rating 4.7）を
使った。margin 0.9242 ≥ 0.60 かつ rating 4.7 ≥ 4.5 なので、`phase_router` は 2 番目の
枝で `oem` を返す —— **1 個も売る前に「自社ブランド」として告知文が組まれる。**
これは `pipeline_test.cljc` が `(is (= "oem" (:phase sku)))` で固定した意図された挙動で、
`phase_router` が決めるのは*どのフェーズで売り始めるか*であってライフサイクルの
現在地ではない。`okaimono:...` は文字列で、登録は起きていない。

## 手順 5 — `discovery`（マットレス / 未知カテゴリ）

```bash
curl -s -X POST http://127.0.0.1:8412/run -H 'content-type: application/json' \
  -d '{"task_type":"discovery","payload":{"category":"mattress"}}' \
| python3 -c 'import json,sys;d=json.load(sys.stdin);s=d["approved_skus"][0];print(json.dumps({"phase":s["phase"],"margin":round(s["margin"],4),"sell_price_jpy":s["sell_price_jpy"],"weight_kg":s["oem_candidate"]["weight_kg"],"notifications":d["operator_notifications"]},ensure_ascii=False,indent=2))'
```

実測:

```json
{
  "phase": "import",
  "margin": 0.858,
  "sell_price_jpy": 35800,
  "weight_kg": 8.5,
  "notifications": [
    "[Ph2 Import Required] 3D Air Fiber Mattress 8cm Washable item_id=1005007792087113 price_jpy=12500 weight_kg=8.5"
  ]
}
```

8.5 kg > 5.0 kg なので 1 番目の枝が当たり `import`。オペレータ通知が 1 件出る
——**この repo が人間に渡す唯一の出力がこれ**（発注はしない）。

未知カテゴリは stub が無いので空で終わる。エラーにはならない:

```bash
curl -s -X POST http://127.0.0.1:8412/run -H 'content-type: application/json' \
  -d '{"task_type":"discovery","payload":{"category":"desk"}}' \
| python3 -c 'import json,sys;d=json.load(sys.stdin);print(json.dumps({"branded_products":d["branded_products"],"oem_candidates":d["oem_candidates"],"approved_skus":d["approved_skus"]}))'
```

```json
{"branded_products": [], "oem_candidates": [], "approved_skus": []}
```

自分の商品行で動かすなら、`payload` に `branded_products` と `oem_candidates` を
直接渡す（渡した行があれば stub は使われない）。

## 手順 6 — `phase_promotion` と、**繋がっていない語彙**

昇格は `discovery` とは別 task。まず `phase_router` が出す語彙（`"import"`）を
そのまま渡す:

```bash
curl -s -X POST http://127.0.0.1:8412/run -H 'content-type: application/json' \
 -d '{"task_type":"phase_promotion","payload":{"rows":[{"sku":"sku:test:1","phase":"import","monthlyGmv":"999999","returnRate":"0.01","marginPotential":"0.9","okaimonoId":"ok:1"}]}}'
```

```json
{"phase":"phase_promotion","promoted":[],"errors":[]}
```

**同じ行の `phase` だけを `":phase/import"` に書き換える**（他は 1 文字も変えない）:

```bash
curl -s -X POST http://127.0.0.1:8412/run -H 'content-type: application/json' \
 -d '{"task_type":"phase_promotion","payload":{"rows":[{"sku":"sku:test:1","phase":":phase/import","monthlyGmv":"999999","returnRate":"0.01","marginPotential":"0.9","okaimonoId":"ok:1"}]}}'
```

```json
{"phase":"phase_promotion","promoted":[{"sku":"sku:test:1","from":":phase/import","to":":phase/oem","okaimonoId":"ok:1"}],"errors":[]}
```

**`discovery` の出力を `phase_promotion` にそのまま流しても何も昇格しない。**
`phase_router` は `"import"`、`phase_promotion` は `":phase/import"` を使い、
変換はこの repo のどこにも無い。閾値を満たしていないのではなく、
**文字列が一致していない**（0 件と 1 件の差はこの 7 文字だけ）。繋ぐなら呼び出し側で
`":phase/" + phase` を噛ませる —— 決定核を触らずに済む。

## 手順 7 — 未知 task は 404

```bash
curl -s -o /tmp/unk.json -w '%{http_code}\n' -X POST http://127.0.0.1:8412/invoke \
  -H 'content-type: application/json' -d '{"task_type":"nope"}'
cat /tmp/unk.json
```

```
404
{"error":"unknown_task","task_type":"nope","known_tasks":["discovery","health","phase_promotion","phase_router","quality_eval","supplier_search","trend_scan"]}
```

`/invoke` は `/run` と同じ扱い。task 名は `task_type` / `taskType` / `graph` の
どれで渡してもよい。**`known_tasks` がこの repo で叩けるものの全部**である。

## 手順 8 — 後片付け

```bash
pkill -f 'hakken.server'
curl -s -m 2 http://127.0.0.1:8412/health || echo "(connection refused = 停止済み)"
git -C "$REPO" status --porcelain
```

実測:

```
(connection refused = 停止済み)
?? clj/.cpcache/
```

`clj/.cpcache/` は tools.deps のクラスパスキャッシュ。**コミットしない**
（`.gitignore` に登録済み）。`clj/bb.edn` に `clean` task があるが、`bb` は
workspace 全体で退役済み（superproject `AGENTS.md`）なので走らない ——
消すなら `rm -rf "$REPO/clj/.cpcache"`。

## 手順 9 — 何を確かめたことになるか

ここまでで確かめたのは **決定核が入力から出力を出すこと**だけである。
確かめていないもの:

- 外部システムとの接続（**この repo に 1 本も無い**。`kotoba_cids` /
  `registered_okaimono_ids` / `announcements` はすべて文字列）
- `AGENTS.md` が記す kotoba KG スキーマ・`ai.gftd.apps.kotobase.kg.*` 呼び出し・
  cross-actor 連携（**コードから参照されていない**。設計文書として読む）
- `wasm/hakken-phase-promotion.wasm`（18.5 MB の WASI 0.2 component。repo 内の
  どこからも参照されず、`clj/` の実装とも独立）

経緯と、これを「未完成」ではなく「ingest を持たない決定核」として記述する判断は
[`adr/0001-hakken-is-a-decision-kernel-without-ingest.md`](adr/0001-hakken-is-a-decision-kernel-without-ingest.md)。
