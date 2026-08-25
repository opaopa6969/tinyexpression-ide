# Loop Engineering 進捗記録

## プロファイル
- profile: issue-fix (adaptive dispatcher) — 反復 2/2
- 前回: audit-triage（9 issue に分解済）→ 反復1で5件修正・4件人間ゲート
- 実行日: 2026-08-06（反復2）

## 分類ルート（adaptive dispatcher）
9 issue を既存 loop へ振り分け。loop 指定なし、severity/性質で分類。

| No. | kind/loop | 根拠 | 実施 |
|-----|-----------|------|------|
| 001 | issue-fix (bug) | `EvalEndpoint.java:160` 部分一致置換 | **修正済** |
| 002 | issue-fix (bug) | `EvalEndpoint.java:71-75` readLine 改行消失 | **修正済** |
| 003 | contract-drift (仕様変更) | spec と実装の不一致、TODO 未解決 | 人間ゲート |
| 004 | issue-fix (bug) | `EvalEndpoint.java:117` cause の null チェック不足 | **修正済** |
| 005 | contract-drift (仕様変更) | spec「文字列として扱われる」と実装 `getAsString()` の不一致 | 人間ゲート |
| 006 | issue-fix (robustness) | `EvalEndpoint.java:58` 無制限スレッドプール | **修正済** |
| 007 | test-gap | テスト0件・CIなし | 人間ゲート（広範囲） |
| 008 | issue-fix (security) | `EvalEndpoint.java:67,135` CORS wildcard | **修正済** |
| 009 | issue-fix (build/deps) | `index.html:223,239` Monaco CDN | 人間ゲート（ビルド判断） |

LOOP_ROUTE: issue-fix

## 実施した最小修正（5件、すべて `EvalEndpoint.java`）

### #001 変数置換の部分一致バグ
- 変更: `substituted.replace("$" + varName, varValue)` → `replaceAll` + 否定先読み `(?![A-Za-z0-9_])`
- `Pattern.quote(varName)` / `Matcher.quoteReplacement(varValue)` でメタ文字をエスケープ
- 検証: `/tmp/opencode/eval-verify/Verify.java` で `$xy + $x` → `2 + 1`（順序依存解消）、`$xyy` → `$xyy`（誤置換しない）、終端/空白/演算子/括弧の各境界で正置換、変数値 `$1` のメタ文字も安全
- 影響: `result.substituted` 表示と `SimpleExpressionEvaluator.evaluate()` 入力の両方が正しくなる

### #002 リクエストボディで改行が消失
- 変更: `body.append(line)` → `if (!first) body.append('\n'); body.append(line)`
- 検証: `line1\nline2\nline3` が保持されることを確認
- 副次的: `//` コメント構文は `SimpleExpressionEvaluator` が未対応（別件、本修正範囲外）
- 仕様判断: `spec-eval-api.md` は `formula` を文字列とし、複数行を明記していないが、Monaco エディタは複数行入力を前提とするため保持するのが整合的。仕様側に「複数行可」を追記すべきだが、spec 変更は人間ゲート

### #004 ExecutionException.getCause() の NPE リスク
- 変更: `Throwable cause = e.getCause() != null ? e.getCause() : e;` で null ガード
- cause=null でも `e` 自体のメッセージで応答可能

### #006 評価スレッドプールが無制限
- 変更: `Executors.newCachedThreadPool` → `ThreadPoolExecutor` (core=max=8, queue=64, `CallerRunsPolicy`)
- ローカル IDE 想定で8スレッド上限。飽和時は呼び出し元スレッドで実行（バックプレッシャー）
- 定数 `EVAL_POOL_SIZE=8`, `EVAL_QUEUE_CAPACITY=64` で調整可能

### #008 CORS が wildcard
- 変更: 環境変数 `TINYEXP_ALLOWED_ORIGINS`（カンマ区切り）で許可 origin を設定可能
- 未設定時は `*` を維持（後方互換、ローカル same-origin IDE 想定）
- 設定時は Origin ヘッダと照合し、許可リストに無ければ `null`（RFC 6454 opaque origin で実質拒否）
- `doPost`/`doOptions` 両方で `corsHeaderFor(req.getHeader("Origin"))` を使用

## ビルド検証
- `mvn -DskipTests compile` → **BUILD SUCCESS**（Java 21, Maven 3.9.9）
- 既存テスト0件のため `mvn test` はスキップ（#007 が人間ゲート）

## 人間ゲート（勝手に進めない）

### #003 resultType パラメータが実装で無視（仕様変更）
- 選択肢: (A) 仕様確定して実装 / (B) spec/README から削除し 400 拒否 / (C) 現状維持を明記
- `SimpleExpressionEvaluator` は `true/false` を `1/0` に変換するロジックを持つが `resultType` と未連動
- **判断が必要**: A/B/C のどれを採用するか

### #005 非文字列の変数値で IllegalStateException（仕様変更）
- `getAsString()` が `{"x": 10}` で `IllegalStateException`
- spec は「値は評価前に文字列として扱われる」だが実装は JSON 型を強制
- 選択肢: `toString()` で緩和 / 型チェック付き取得 / 仕様を変数値=文字列のみに制限
- **判断が必要**: 文字列強制か JSON 型維持か

### #007 テスト0件・CI設定なし（広い影響範囲）
- 選択肢: 最小限か網羅的か / CI 実行環境（OS・JDK・キャッシュ） / JUnit 4 のままか 5 移行か
- **判断が必要**: テスト範囲・CI 環境・JUnit 版

### #009 Monaco を CDN から読み込み（ビルド判断）
- 選択肢: ローカルバンドル（`src/main/resources/static/vendor/monaco/`）か npm から取得するビルド手順か
- バンドルサイズ増（数 MB）を許容するか
- **判断が必要**: バンドル方針・ビルド手順

## PR / merge 状態
- `git remote -v` → 空（remote 未設定）。PR 作成不可。
- profile=issue-fix は one active PR per repo だが、remote がないため PR 作成は行わない。
- 現在のブランチ: `docs/market-research-2026-07-31` に market research ドキュメントと今回の `EvalEndpoint.java` 修正が混在。
- **提案**: 修正は別ブランチ（`fix/eval-endpoint-issues-001-008` 等）に切り出すべき。ブランチ運営方針をユーザー判断へ。

## 反復サマリ（反復1）
- **観測事実**: 9 issue の根拠を行番号で再照合。すべて事実。`EvalEndpoint.java` 5箇所、`SimpleExpressionEvaluator.java` は #003/#005 の判断材料として参照。
- **仮説**: #001/#002/#004/#006/#008 は仕様を変えない安全な最小修正。#003/#005/#007/#009 は仕様/広範囲/ビルド判断を含む。
- **実施内容**: `EvalEndpoint.java` に5件の最小修正。外部ライブラリ追加なし。`SimpleExpressionEvaluator.java` は未変更。
- **検証結果**: `mvn compile` 成功。#001/#002 は `/tmp/opencode/eval-verify/` で振る舞い検証。#004/#006/#008 は静的確認（既存テスト0件のため実機テスト不可）。
- **次の判断**: 4件の人間ゲート（#003/#005/#007/#009）と、ブランチ/remote 方針についてユーザー判断を待つ。

## 反復サマリ（反復2）
- **観測事実**:
  - `git diff master..HEAD -- EvalEndpoint.java` で反復1の5件修正がすべて入っていることを確認（227行、imports 含む）。
  - `mvn -DskipTests compile` → BUILD SUCCESS（Java 21.0.9 Oracle, Maven 3.9.9）。反復1と同様。
  - `src/test/java/org/unlaxer/tinyexpression/ide/` は空、`.github/` なし → #007 事実再確認。
  - `SimpleExpressionEvaluator.java` は未修正（#003/#005 の判断材料）。
  - `git remote -v` → 空（PR作成不可、反復1と同じ）。
- **仮説**:
  - 反復1の5件修正は安定。追加の安全な最小修正候補は #005 のみだが、issue 本文が明示的に「仕様変更」と分類し spec 側を変える余地も残しているため、反復2では勝手に修正しない。
  - #003/#007/#009 は反復1と同じく人間ゲート維持。
- **実施内容**: 反復2では新規修正なし。修正済5件のビルド再検証のみ。#005 について spec 準拠の修正案を提示（下記）。
- **検証結果**: `mvn -DskipTests compile` BUILD SUCCESS。テスト0件のため実機テスト不可（#007 が人間ゲート）。
- **次の判断**: 4件の人間ゲート（#003/#005/#007/#009）と、ブランチ/remote 方針についてユーザー判断を待つ。

### #005 の spec 準拠修正案（人間ゲート、未適用）
spec-eval-api.md:13 は「`variables`: 任意の JSON オブジェクト。値は評価前に文字列として扱われる」と明記。
実装 `EvalEndpoint.java:124` は `entry.getValue().getAsString()` で JSON 文字列型を強制し、`{"x": 10}` で `IllegalStateException`。

**spec に従う最小修正案**（仕様変更ではなく準拠と解釈）:
```java
// 現状: variables.put(entry.getKey(), entry.getValue().getAsString());
// 修正案: JSON 型を問わず文字列表現へ
JsonElement v = entry.getValue();
String value;
if (v.isJsonNull()) value = "";
else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) value = v.getAsString();
else value = v.toString(); // 数値 10 → "10"、真偽 true → "true"、配列/オブジェクトは JSON 表現
variables.put(entry.getKey(), value);
```
- spec:13 の「文字列として扱われる」に合致。`{"x": 10}` → `"10"`、`{"x": true}` → `"true"`。
- `SimpleExpressionEvaluator` は `true`/`false` を `1/0` に変換するため、`"true"` が渡されても数値化される（既存ロジックと整合）。
- **判断が必要**: spec:13 を維持して実装を準拠させる（上記案）か、spec 側を変数値=文字列のみに制限するか。issue 本文は「将来のランタイム接続で型付き変数を想定するか確認が必要」と注記しており、spec 側を変える余地も残している。

## 外部情報・再現手順
- `git remote -v` → 空（2026-08-06 反復2で再確認）
- `mvn -DskipTests compile` → BUILD SUCCESS（Java 21.0.9 Oracle, Maven 3.9.9）※ `/home/opa/.sdkman/candidates/maven/current/bin/mvn` を使用
- 検証プログラム: `/tmp/opencode/eval-verify/Verify.java`, `Verify2.java`（一時ファイル、repo に含めず）
- 行番号根拠: `EvalEndpoint.java` 修正前 195行 → 修正後 227行

## LOOP_STATE
done

## 反復サマリ（audit-triage 反復1、2026-08-16）
- **観測事実**:
  - 9 issue ファイル（`docs/issues/001-009`）すべて行番号根拠あり・事実・重複なしを再確認。
  - `EvalEndpoint.java`（227行）は反復1（issue-fix）で5件修正済み（#001/002/004/006/008）。残り4件（#003/005/007/009）は人間ゲート。
  - 9 issue ファイルには「根拠/影響範囲/改善案/判断待ち/重複チェック」はあるが、**実行契約4の機械可読フィールド（kind/loop/priority/depends_on/acceptance）が未記録**だった。
  - 元監査レポートに含まれない新規候補はなし。重複・推測・低価値候補の除外対象もなし（9件すべて根拠あり）。
- **仮説**: audit-triage プロファイルの今回の範囲は「根拠のある改善候補だけを重複なく issue 化」すること。新規 issue 化候補は元監査に既に9件として切り出されており、不足していたのはメタデータ（kind/loop/priority/depends_on/acceptance）のみ。
- **実施内容**: 9 issue ファイルすべてに機械可読ブロックを追記。各 issue の kind/loop は性質に従い分類:
  - bug / issue-fix: #001, #002, #004, #006, #008
  - contract / contract-drift: #003, #005（仕様判断待ち）
  - test / test-gap: #007（広範囲判断待ち）
  - architecture / architecture-decision: #009（ビルド判断待ち）
  - priority は各 issue 本文の high/medium/low に整合。
  - depends_on はすべて `[]`（依存関係なし）。
  - acceptance は各 issue 本文の改善案・判断待ちから抽出した検証可能条件。
- **検証結果**: 9 ファイルすべて edit 成功。コード変更・PR・merge は実行契約により行わない。
- **次の判断**: 今回は audit-triage の完了。次に進める loop は以下の人間ゲート解消後に各 loop へ:
  - #003/#005 → `contract-drift`（仕様判断確定後）
  - #007 → `test-gap`（テスト範囲・CI 環境判断確定後）
  - #009 → `architecture-decision`（バンドル方針判断確定後）
  - #001/#002/#004/#006/#008 → 修正済み（`issue-fix` 完了）
