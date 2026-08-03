# Issue 003: resultType パラメータが実装で無視 (medium)

## 根拠
- `EvalEndpoint.java:150` の `evaluate(formula, variables, resultType)` は受け取った `resultType` を参照しない（実装内で使用箇所なし）
- `EvalEndpoint.java:89-90` で `resultType` を読み、デフォルト `"number"` を設定するが、評価に反映されない
- `README.md:80` と `docs/spec-eval-api.md:14` は `resultType: "number"|"string"|"boolean"` を掲げる
- `docs/spec-eval-api.md:15` で「受け付けられる値の意味と、結果への反映方法は未確定。TODO: 要確認」と矛盾

## 影響範囲
- `README.md`, `docs/spec-eval-api.md`, `EvalEndpoint.java`, `index.html`
- ユーザーが `resultType: "string"` や `"boolean"` を送っても `number` として扱われ、`SimpleExpressionEvaluator` は数値のみ返す

## 改善案
- (A) 仕様を確定して実装する（例: `string` は評価結果をそのまま、`boolean` は `0/1` を `false/true` に変換）
- (B) 未使用なら README/spec から `resultType` を削除し、API は `resultType` 受け取りを 400 で拒否
- (C) 現状維持（受け取るが無視）を明記して spec の TODO を解消

## 判断待ち (人間ゲート: 仕様変更)
- 仕様未確定のため、(A)/(B)/(C) のどれを採用するかはユーザー判断
- `SimpleExpressionEvaluator` は `true`/`false` を `BigDecimal.ONE/ZERO` に変換するロジックを持つ（`SimpleExpressionEvaluator.java:142-149`）が、`resultType` とは連動していない

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。
- ただし「仕様側も未確定」のため、実装側のバグではなく仕様判断待ち。
