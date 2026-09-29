# ADR-0001 — hakken は「ingest を持たない決定核」である

- **Status**: accepted
- **Date**: 2026-08-09
- **Scope**: `cloud-itonami/hakken`（superproject `com-junkawasaki/root` の west project）

## Context

この repo は 2026-06-30 に `gftdcojp/ai-gftd-apps-gftdcojp/60-apps/ai-gftd-project-hakken`
から切り出され、以降 commit は 2 本（EDN datomize のみ）。**root に README が無く、
「何が在って何が無いか」を述べる場所が repo 内に存在しなかった。**

代わりに root にあったのは `AGENTS.md` 4,330 B で、そこには kotoba KG スキーマ・
`ai.gftd.apps.kotobase.kg.*` エンドポイント表・SPARQL クエリ・5 つの cross-actor
連携先が書かれている。**読むと「繋がっている系」に読める。**

実測（2026-08-09、手順は `docs/operator-quickstart.md`。すべて実行して出力を確認）:

1. **外部呼び出しが 1 本も無い。** `clj/src/` 13,924 B のどこにも HTTP クライアントは
   無く、`AGENTS.md` が挙げる `kakaku` / `kaimono-review` / `okaimono` / `tsukuru` /
   `kotoba` のいずれも参照されない。`gap_analysis` の `kotoba_cids` は
   `"local-cid:" + id`、`okaimono_register` の ID は `"okaimono:" + item_id`、
   `social_announce` の告知は文字列。**どれも副作用を起こさない。**

2. **決定核は本当に動く。** `kbb -M:test` が 6 tests / 21 assertions で緑。
   サーバを上げて `POST /run` を叩くと、枕（0.5 kg・rating 4.7・margin 0.9242）は
   `oem`・売価 8,800 円、マットレス（8.5 kg・margin 0.858）は `import`・35,800 円 +
   オペレータ通知 1 件を返す。**閾値・丸め・grade 足切りはすべて実際に効いている。**

3. **`phase_router` はライフサイクルではなく分類器。** 判定順は
   import（重量物）→ oem（高粗利・高評価）→ dropship で、最初に当たった 1 つを採る。
   軽くて粗利が高い候補は `dropship` を経ずに `oem` に入り、**まだ 1 個も売っていない
   SKU に「【新着 自社ブランド】」の告知文が組まれる。** これは
   `pipeline_test.cljc` が `(is (= "oem" (:phase sku)))` で固定した意図された挙動だが、
   `AGENTS.md` の「Ph1 Dropship → Ph2 Import → Ph3 OEM」表は段階的な前進に読める。

4. **`phase_router` と `phase_promotion` の語彙が繋がっていない。** 前者は `"import"`、
   後者は `":phase/import"` しか受けず、変換はこの repo に無い。同一の行で phase
   文字列だけを入れ替えると `promoted` が 0 件と 1 件に分かれる（実測、手順 6）。
   **`discovery` の出力を `phase_promotion` に流す経路は存在しない** —— 閾値の問題
   ではなく 7 文字の問題。

5. **`wasm/hakken-phase-promotion.wasm` 18.5 MB は孤児。** WASI 0.2 component
   （binary version `0x1000d`）で、`AGENTS.md` のファイル一覧に「preserved generated
   artifact」として現れる以外、repo 内のどこからも参照されない。`clj/` の実装とも
   独立していて、どちらが正本かを述べたものが無い。

## Decision

**この repo を「未完成のパイプライン」ではなく『ingest を持たない決定核
（decision kernel）』として記述する。** 根拠は上記 1・2 —— 判断は完成していて、
入力の調達だけが無い。この 2 つを混ぜて「scaffold」と一語で呼ぶと、**動くものを
動かないと誤読させる**（実際、`AGENTS.md` の `status: scaffold (2026-05-27)` が
そう読める）。

具体的に、この判断が縛るもの:

- **root `README.md` は、実装が「する」ことと「しない」ことを分けて述べる。**
  `AGENTS.md` は設計文書であって実装状況ではない、と README 側で明示する
  （`AGENTS.md` 自体は設計意図の記録として残す。消すと何を目指していたかが失われる）。
- **`docs/operator-quickstart.md` は実際に踏んだ手順と出力だけを載せる。**
  9 手順すべて 2026-08-09 に実行済み。上記 3・4 は quickstart の手順 4・6 として
  再現できる形で置いた（誰でも 0 件と 1 件の差を自分で見られる）。
- **外部接続を足すときは、決定核に HTTP クライアントを埋めない。** 今の
  `pipeline.cljc` は入力を渡されればそれを使い、渡されなければ stub を使う形に
  なっている。呼び出し側が行を渡す形を保てば、決定核は純関数のままで、テスト可能性を
  失わない。語彙の橋（`":phase/" + phase`）も呼び出し側に置く。

## 採らなかった選択肢

- **`clj/src` → root `src` に移す。** 艦隊の成熟度 scan
  （`scripts/itonami-maturity-scan.cljs`）は repo root の `src/` と `test/` だけを
  数えるので、この repo は 13,924 B の実装と 4,945 B のテストを持ちながら
  **substrate 軸・test 軸とも 0bp** と測られている。移せば 2 軸が同時に上がる。
  **採らない** —— 動作は 1 バイトも変わらず、上がるのは測定値だけで、それは
  水増しである（`itonami-maturity-improve` skill が構造的に禁じている形）。
  実装が `clj/` に居るのは Dockerfile と deps.edn の配置に沿った既存の判断で、
  変える理由が計測しかないなら変えない。**測り方の側の限界としてここに記録する。**
- **`AGENTS.md` の未実装部分を削除する。** 削ると「何を目指していたか」が repo から
  消える。README で「設計文書であって実装ではない」と述べるほうが、情報を失わずに
  誤読を止められる。
- **語彙の不一致を `pipeline.cljc` で直す。** 直せるが、**これは 1 行の変更で済む
  代わりに、どちらの語彙を正本にするかという未決の判断を暗黙に決めてしまう**
  （`phase_router` 側の bare string か、KG が使う `":phase/..."` keyword か）。
  今回は記述にとどめ、決める人に判断を残す。
- **`wasm/` を消す。** 18.5 MB は superproject の large-binary 方針（B2 + DataLad）に
  照らせば git に置くべきものではないが、**何であるかを誰も述べていないものを、
  述べる前に消さない。** 由来を知る人が居るうちに正本を決めるべき事項として残す。

## Consequences

- 読み手は、この repo が**何を渡せば何を返すか**を README と quickstart だけで
  判断できる。設計文書との差分を自分で照合する必要が無くなる。
- 上記 3・4・5 は**未解決のまま名指しで残る**。解決したふりをしない代わりに、
  次に触る人がどこから手を付けるかを選べる。
- substrate 軸 / test 軸 0bp は当面そのまま。**上げたければ計測側を直す**
  （scan が `*/src` を見る）か、実装上の理由で配置を変えるかであって、
  スコアのために移動はしない。
