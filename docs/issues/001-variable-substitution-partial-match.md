# Issue 001: 変数置換の部分一致バグ (high)

## 根拠
`src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java:160`
```java
substituted = substituted.replace("$" + varName, varValue);
```
`String.replace` は部分一致で置換する。`$` + 変数名の境界を区切らないため、`$xy` と `$x` が共存すると順序依存で誤置換される。

## 再現例
- `variables = { "x": "1", "xy": "2" }`, `formula = "$xy + $x"`
- `x` が先に処理されると `"$xy"` の `$x` 部分だけ `1` に置換され `"1y + 1"` になる
- `LinkedHashMap` の挿入順に依存するため、呼び出し側の JSON キー順で結果が変わる

## 影響範囲
- `src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java`
- `/api/eval` の全呼び出しで変数置換結果が誤る可能性
- `substituted` フィールドの表示と、その後の `SimpleExpressionEvaluator.evaluate()` の入力の両方が誤る

## 改善案
- 変数参照を正規表現 `\$varName\b`（単語境界）で置換
- または長い変数名から順に置換（ただし `\$xy` を先に処理しても `\b` がないと `$xyy` で誤置換されるため、正規表現ベースが安全）

## 判断待ち (人間ゲート)
- 修正優先度（high だが、現実の変数名命名規約次第で影響度が変わる）
- TinyExpression の変数名規則（識別子文字種・長さ）に依存するため、仕様確認後に正規表現を決めるべき

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。
