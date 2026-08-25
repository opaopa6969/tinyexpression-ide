# TinyExpression IDE — MCP 化調査（Phase 1）

## 概要

TinyExpression 式をブラウザ上で編集・評価・診断する Web IDE。Jetty 上で Monaco Editor 静的配信、LSP over WebSocket、`POST /api/eval` 評価 API を提供する。現状は `StubLspServer` と簡易評価器（`SimpleExpressionEvaluator`）の MVP で、本命の `TinyExpressionP4LanguageServerExt` / `AstEvaluatorCalculator` は未接続。volta カタログ上は `operational_status=retired`、`hosted_url=expr.unlaxer.org` だが MCP バックエンドなし。

- **リポジトリ種別**: service（常駐 Jetty サーバあり）
- **言語/ランタイム**: Java 21 / Maven / Jetty 11
- **規模**: Java 5ファイル・HTML 1ファイル・テスト0件
- **依存**: `unlaxer-common` / `unlaxer-dsl` 2.8.0、LSP4J 0.23.1、Gson 2.10.1

## 判定と理由

**判定: `wrap`** — 既存 API を薄く包む

既に Jetty HTTP サーバが常駐し `POST /api/eval` と WebSocket `/lsp` を持つため、MCP 化は既存 API を薄く包む wrap が最適。新規サーバプロセスは不要（既に常駐）、`healthz` と `PORT` 環境変数と `volta.service.json` だけ追加すれば volta に参加できる。

ただし以下の前提条件がある:
- 評価器が MVP 簡易版で本命ランタイム未接続 — MCP tool としての価値が限定される
- テスト0件・CI なし（issue #007）— 品質保証が弱い
- `operational_status=retired` で `expr.unlaxer.org` は稼働していない — 再稼働が必要

MCP 化の価値はエージェントが TinyExpression 式を評価・診断できる tool にあり、他サービス（`tinyexpression` ライブラリ等）との協調の核になる。

## 公開候補

| kind | name | io | 副作用 | 長時間 | 対象 |
|------|------|----|--------|--------|------|
| tool | `evaluate` | `{formula, variables, resultType?} → {result, error, formula, substituted}` | read | false | `EvalEndpoint.java:doPost()` — `POST /api/eval` を薄く包む |
| tool | `diagnose` | `{text} → {diagnostics, completions}` | read | false | `StubLspServer.java` — WebSocket `/lsp` の LSP メッセージを薄く包む |
| resource | `spec` | — | — | — | `tinyexpression-ide://spec`（評価APIの仕様） |
| resource | `guide` | — | — | — | `tinyexpression-ide://guide`（IDEの使い方） |
| skill | `formula-eval-workflow` | — | — | — | locality: repo（式の変数自動検出と評価の組み立て方） |

### tool 詳細

#### `evaluate`
- **入力**: `{formula: string, variables?: {key: string}, resultType?: string}`
- **出力**: `{result: string|null, error: string|null, formula: string, substituted: string}`
- **副作用**: read（式を評価するだけ、状態を変更しない）
- **長時間**: false（5秒タイムアウト付き、現状は長時間処理なし）
- **マップ先**: `EvalEndpoint.java:88` の `doPost()` — `POST /api/eval` を薄く包む
- **注意**: 現状は `SimpleExpressionEvaluator`（四則演算のみ）。本命 `AstEvaluatorCalculator` 接続後に全 TinyExpression 構文をサポート予定

#### `diagnose`
- **入力**: `{text: string}`
- **出力**: `{diagnostics: [{range, severity, message}], completions: [string]}`
- **副作用**: read
- **長時間**: false
- **マップ先**: `StubLspServer.java` — WebSocket `/lsp` 上の LSP メッセージを薄く包む
- **注意**: 現状は Stub（キーワード補完 + 括弧バランスチェックのみ）。本命 `TinyExpressionP4LanguageServerExt` 接続後に完全な診断を提供予定

## 組み合わせ例

1. `tinyexpression-ide__evaluate` → `apbu__validate` — 式の評価結果を JSON Schema で検証する
2. データパイプラインが `tinyexpression-ide__evaluate` で式を評価し、結果を `nanori__parse` に流す
3. `tinyexpression-ide__diagnose` で式の構文エラーを検出し、`volta_skill__resolve` で修正手順を得る

## 依存と協調

| 相手 repo | 方向 | 能力 | 現存 | 備考 |
|-----------|------|------|------|------|
| tinyexpression | depends_on | `AstEvaluatorCalculator` / `TinyExpressionP4LanguageServerExt`（本命評価器・LSP。現状は MVP 簡易版で未接続） | false | `pom.xml` で `unlaxer-common` / `unlaxer-dsl` 2.8.0 に依存。本命ランタイム接続は README Roadmap #1/#2 だが未着手。`tinyexpression` リポジトリは volta カタログに library として登録済みだが MCP バックエンドなし |
| unlaxer-parser | depends_on | TinyExpression 文法のパーサ・AST 生成基盤（`unlaxer-dsl` 経由で間接依存） | false | volta カタログに library として登録済み、MCP バックエンドなし |
| tinyexpression | provides_to | 式評価 tool（`evaluate`）と診断 tool（`diagnose`）。`tinyexpression` ライブラリがサーバ化された場合、評価能力はそちらに寄せるべき | false | `tinyexpression` はライブラリであり MCP サーバを持たない。将来 `library-serve` で MCP 化されれば、評価 tool はそちらに統合し、IDE 側は診断/UI に特化するのが自然 |

**協調の要否**: あり（Phase 2 で issue-hub にて）。`tinyexpression` ライブラリの MCP 化（`library-serve`）が決まった場合、評価 tool の所属を調整する必要がある。本 Phase 1 では issue 立てない。

## ライブラリのサーバ化

該当なし（`tinyexpression-ide` は既に service。`library_serve.needed=false`）。

既存 Jetty サーバに以下を追加するだけで volta に参加可能:
1. `healthz` エンドポイント（`/healthz` が 200 を返す）
2. `PORT` 環境変数によるポート指定（現状は args[0] で受け取るのみ）
3. `bind 0.0.0.0`（現状は暗黙に 0.0.0.0 だが明示すべき）
4. `volta.service.json` manifest をリポジトリ root に配置
5. systemd user unit または docker で常駐化

**見積り**: S（小規模。既存サーバへの薄い追加のみ）

## リスク

- **MVP 評価器の限界**: `SimpleExpressionEvaluator` は四則演算のみ。本命 TinyExpression の式（`if`/`match`/`string`/`boolean` 等）を評価できないため、MCP tool として公開するとエージェントが誤解する恐れがある。tool description に「MVP 簡易版・本命未接続」を明記すべき
- **retired 状態**: `operational_status=retired` で `expr.unlaxer.org` は稼働していない。MCP 公開前に稼働状態を復旧する必要がある
- **テスト0件・CI なし**（issue #007）: MCP tool の品質保証が弱い
- **CORS / bind**: MCP ファサードは LAN 越しに来るため `bind 0.0.0.0` と CORS 許可（`TINYEXP_ALLOWED_ORIGINS`）が必要
- **タイムアウト**: 5秒の評価タイムアウトあり。現状は長時間処理なし（job 型不要）
- **秘密情報・課金**: なし（ローカル評価のみ）

## 持ち主への質問

1. `operational_status=retired` だが、MCP 化して再稼働させる予定はあるか？
2. 本命 `TinyExpressionP4LanguageServerExt` / `AstEvaluatorCalculator` の接続時期は？（MCP tool の価値がこれに依存する）
3. 将来 `tinyexpression` ライブラリ自体が MCP サーバ化（`library-serve`）された場合、評価 tool はそちらに寄せるか？IDE 側は診断/UI に特化するか？
4. `resultType` パラメータの仕様が未確定（issue #003）。MCP tool の入力スキーマに含めるか？
5. 非文字列の変数値の扱いが未確定（issue #005）。MCP tool で `variables` を文字列のみに制限するか？
