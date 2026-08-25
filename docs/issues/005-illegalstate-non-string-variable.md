# Issue 005: 非文字列の変数値で IllegalStateException (medium)

## 根拠
`src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java:97`
```java
variables.put(entry.getKey(), entry.getValue().getAsString());
```
`JsonElement.getAsString()` は、要素が文字列でない場合（数値・真偽値・配列等）に `IllegalStateException` を投げる（Gson 仕様）。
`{"x": 10}` のような数値値で、`getAsString()` が `IllegalStateException` となる。

## 影響範囲
- `src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java`
- クライアントが数値・真偽値を変数値として送ると 500 系エラー
- 仕様 `spec-eval-api.md:13` は「値は評価前に文字列として扱われる」とあるが、実装は JSON 型を強制

## 改善案
- `getAsString()` を `toString()` に置換（Gson は `toString()` で JSON 表現を返す）
- または型チェック付き取得（`isJsonPrimitive() && getAsJsonPrimitive().isString()` で分岐）
- 仕様「文字列として扱われる」に合わせるなら、数値 `10` を `"10"` に強制変換するのが整合的

## 判断待ち (人間ゲート: 仕様変更)
- 変数値を常に文字列に強制するか、JSON 型を維持して評価器で扱うかは仕様判断
- 現状の spec 記述「文字列として扱われる」に合わせる案が無難だが、将来のランタイム接続で型付き変数を想定するか確認が必要

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。

---

kind: contract
loop: contract-drift
priority: medium
depends_on: []
acceptance:
  - 変数値の型取り扱い（文字列強制 or JSON 型維持）が仕様判断で確定すること（人間ゲート）
  - 確定した仕様に従い、非文字列値で `IllegalStateException` が発生しないこと、または spec が文字列のみに制限されること
