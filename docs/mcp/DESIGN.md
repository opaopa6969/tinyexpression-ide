# TinyExpression IDE — MCP 化設計（Phase 2）

## 1. namespace と種別

- **namespace**: `tinyexpression-ide`
- **種別**: wrap（既存 Jetty HTTP サーバの API を薄く包む）
- **割当表**: MCPIFY-phase2-plan.md #72 — namespace=`tinyexpression-ide`, port=9264

### 既存サービスとの関係

| namespace | repo | 能力 | 関係 |
|-----------|------|------|------|
| `tinyexpr` | tinyexpression | 式評価エンジン（library-serve, port 9237） | 本命評価器。IDE の `evaluate` は現状 MVP 簡易版だが、将来的に `tinyexpr__evaluate` に寄せることを想定 |
| `unlaxer` | unlaxer-parser | パーサ基盤（library-serve, port 9228） | TinyExpression 文法のパーサ。IDE は `unlaxer-dsl` 経由で間接依存 |

`tinyexpression-ide` は既存カタログに retired 状態で登録済み（id=`tinyexpression-ide`, hostname=`expr.unlaxer.org`）。本 Phase 2 で MCP バックエンドを有効化し再活性化する。

## 2. tools 表

| name | 目的 | 入力 schema（要点） | 出力の形 | 副作用 | dry-run | job 型 | 所要時間 | min_role |
|------|------|---------------------|----------|--------|---------|--------|----------|----------|
| `evaluate` | TinyExpression 式を評価する（MVP 簡易版: 四則演算＋変数置換） | `{formula: string, variables?: {string: string}, resultType?: string}` | `{result: string\|null, error: string\|null, formula: string, substituted: string}` | read | なし | なし | <5s | MEMBER |
| `validate` | TinyExpression 式の構文を検証する（括弧バランス＋基本的な診断） | `{formula: string}` | `{valid: boolean, diagnostics: [{range: {line, character}, severity: string, message: string}], formula: string}` | read | なし | なし | <1s | MEMBER |

### 設計判断

- `evaluate` は既存 `POST /api/eval` を MCP tool として wrap。`SimpleExpressionEvaluator`（四則演算のみ）の制限を description に明記。
- `validate` は `StubLspServer` の `publishDiagnostics` ロジック（括弧バランスチェック）を tool として公開。LSP over WebSocket を外部から叩くより、直接ロジックを呼ぶ。
- `diagnose`（survey 候補）は `validate` に統合（診断＝バリデーション）。
- `resultType` は現状未実装（issue #003）だが入力スキーマに含め、将来的な拡張ポイントを残す。
- 全 tool に `readOnlyHint: true` を付ける（破壊的操作なし）。

## 3. resources 表

| uri | 内容 | mime |
|-----|------|------|
| `tinyexpression-ide://spec` | バックエンド仕様（namespace, capabilities, compositions, depends_on, health） | application/json |
| `tinyexpression-ide://guide` | IDE の使い方（式の書き方、変数構文、評価API、MCP tool 利用例） | text/markdown |
| `skill://formula-eval-workflow` | 式評価ワークフローの手続き知識（SKILL.md 形式） | text/markdown |

`<ns>://spec` はサーバ起動時に登録済み tool から生成し、`compositions` / `depends_on` を手で書く。

## 4. prompts / skills

### skill: `formula-eval-workflow`

- **用途**: 式に含まれる `$variableName` を自動検出し、変数マップを組み立てて `evaluate` に渡す手順
- **locality**: repo（このリポジトリでしか意味がない手順）
- **applies_when**: `goal eq "evaluate tinyexpression formula"` または `has_file src/main/java/org/unlaxer/tinyexpression/ide`
- **requires**: `tinyexpression-ide__evaluate`
- **min_role**: MEMBER
- **配置**: `docs/skills/formula-eval-workflow/SKILL.md` + resource `skill://formula-eval-workflow`

## 5. 組み合わせ例

1. **式評価 → バリデーション**: `tinyexpression-ide__evaluate({formula: "1+$x*2", variables: {x: "10"}})` → 結果 `21`。事前に `tinyexpression-ide__validate({formula: "1+$x*2"})` で構文チェック。
2. **IDE 診断 → 修正手順**: `tinyexpression-ide__validate` で構文エラー検出 → `skill__resolve(goal: "fix tinyexpression syntax error")` で修正手順を取得。
3. **将来の統合（暫定）**: `tinyexpression-ide__evaluate` で簡易評価 → `tinyexpr__evaluate` で本命評価（`tinyexpr` が MCP 化済みの場合）。`result` を比較して MVP と本命の差異を確認。

## 6. 依存と協調

| 相手 repo | 方向 | 入口 | 合意したいこと | 暫定案 |
|-----------|------|------|----------------|--------|
| tinyexpression | depends_on | `tinyexpr__evaluate`（port 9237 で稼働中） | IDE の `evaluate` を `tinyexpr` に寄せるか、IDE 側で残すか | 暫定: IDE 側 `evaluate` は MVP 簡易版として残し、本命は `tinyexpr__evaluate` を使う。description に両者の違いを明記 |
| unlaxer-parser | depends_on | `unlaxer__parse`（port 9228 で稼働中） | TinyExpression 文法のパーサを IDE の `validate` に使うか | 暫定: 現状は `StubLspServer` の簡易診断（括弧バランス）で進め、本命パーザ接続後に `unlaxer__parse` に差し替え |

### issue-hub での協調

Phase 2 で以下の issue を issue-hub に登録する（ラベル `mcp-coordination`）:

1. **→ tinyexpression**: `[mcp] tinyexpression-ide ↔ tinyexpr: evaluate tool の所属調整` — IDE 側 `evaluate` と `tinyexpr__evaluate` の重複・将来的な統合方針
2. **→ unlaxer-parser**: `[mcp] tinyexpression-ide ↔ unlaxer: validate のパーサ利用` — `unlaxer__parse` を IDE の `validate` に将来的に使うか

返答を待たず暫定仕様で実装を進める。

## 7. 非対応にした候補

| 候補 | 理由 |
|------|------|
| `diagnose`（survey にあり） | `validate` に統合。LSP over WebSocket を外部から叩くのは MCP クライアントに WebSocket を要求するため不適 |
| 補完（completions）tool | `StubLspServer` のキーワード補完は LSP クライアント向けであり、MCP tool としての価値が低い（エージェントは式を自分で書ける） |
| hover tool | 同上。UI 向け機能であり MCP tool としての価値が低い |

## 8. 参加方法

| 項目 | 値 |
|------|-----|
| id | `tinyexpression-ide` |
| hostname | `expr.unlaxer.org`（既存。CF ワイルドカードで *.unlaxer.org は解決済み） |
| port | 9264（割当表 #72、`machine_ports` で空き確認済み） |
| host | 192.168.1.50（prod） |
| runtime | systemd（Java JAR） |
| auth | minRole: MEMBER |
| health_check | /healthz |
| MCP path | /mcp |
| MCP namespace | tinyexpression-ide |
| MCP timeoutMs | 110000 |

### volta.service.json（概要）

```json
{
  "id": "tinyexpression-ide",
  "name": "TinyExpression IDE",
  "description": "TinyExpression 式の評価・検証 MCP（MVP 簡易評価器）",
  "type": "docker",
  "hostname": "expr.unlaxer.org",
  "port": 9264,
  "host": "192.168.1.50",
  "runtime": "docker",
  "auth": "minRole:MEMBER",
  "health_check": "/healthz",
  "tags": ["mcp", "tinyexpression", "ide", "evaluator"],
  "repo_url": "https://github.com/opaopa6969/unlaxer-workspace/tinyexpression-ide",
  "mcp": {
    "enabled": true,
    "port": 9264,
    "path": "/mcp",
    "namespace": "tinyexpression-ide",
    "min_role": "MEMBER",
    "timeoutMs": 110000,
    "description": "TinyExpression 式の評価・検証（MVP 簡易版）"
  }
}
```

## 9. テスト方針

MCP クライアントで以下の e2e を実行:

1. `GET /healthz` → 200, `{ok: true, name: "tinyexpression-ide", version: "0.1.0"}`
2. `tools/list` → `evaluate`, `validate` が含まれる
3. `evaluate({formula: "1+2*3"})` → `result: "7"`, `error: null`
4. `evaluate({formula: "$x*2", variables: {x: "5"}})` → `result: "10"`
5. `evaluate({formula: ""})` → `error` 含む
6. `validate({formula: "(1+2"})` → `valid: false`, diagnostics に括弧エラー
7. `validate({formula: "1+2"})` → `valid: true`, diagnostics 空
8. `resources/list` → `tinyexpression-ide://spec`, `tinyexpression-ide://guide`, `skill://formula-eval-workflow`
9. `resources/read` spec → JSON に namespace, capabilities が含まれる

テストは Java の JUnit 4 で HTTP エンドポイントを直接テストする（MCP SDK for Java がないため、Streamable HTTP の raw テスト）。

## 実装方針

既存 Jetty サーバ（`IdeMain.java`）に以下を追加:

1. **`/healthz` サーブレット**: `HealthEndpoint.java` — `{ok: true, name, version}` を返す
2. **`/mcp` サーブレット**: `McpEndpoint.java` — Streamable HTTP で MCP プロトコルを処理
3. **`PORT` 環境変数**: `IdeMain.java` で `System.getenv("PORT")` を優先、次に args[0]、最後にデフォルト
4. **MCP サーバロジック**: `McpServerHandler.java` — tools/list, tools/call, resources/list, resources/read を処理
5. **`content-encoding: identity`**: 全応答に付与
6. **bind 0.0.0.0**: Jetty のデフォルトで既に 0.0.0.0 だが明示

MCP プロトコルは JSON-RPC 2.0 over HTTP POST（Streamable HTTP）。Java で MCP SDK がないため、JSON-RPC を直接ハンドルする軽量実装とする。
