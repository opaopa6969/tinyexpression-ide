# Issue 002: リクエストボディで改行が消失 (high)

## 根拠
`src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java:71-75`
```java
try (BufferedReader reader = req.getReader()) {
    String line;
    while ((line = reader.readLine()) != null) {
        body.append(line);
    }
}
```
`readLine()` は行末の改行を削除し、`body.append(line)` で改行を含めない。複数行の式が一行に圧縮される。

## 影響
- 複数行式の改行が消失し、単一行になる
- `//` コメントが行末までの想定だと、次行がコメント化または非コメント化される
- `SimpleExpressionEvaluator` は `skipWhitespace` で空白/改行を飛ばすが、コメント構文は未対応のため、`//` 以降が式として評価されて構文エラーになる（副次的な問題）
- 仕様 `spec-eval-api.md` は `formula` を文字列とするが、複数行の取り扱いを明記していない

## 影響範囲
- `src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java`
- `index.html` から送信される式（Monaco の複数行入力）

## 改善案
- `reader.read(char[])` で一括読み込み
- または `readLine()` 結果を `\n` で再結合
- あわせて `SimpleExpressionEvaluator` が `//` コメントを処理するか確認が必要

## 判断待ち (人間ゲート)
- `formula` を単一行前提とするか複数行許容にするか（仕様判断）
- コメント構文 `//` を MVP 評価器でサポートするか

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。

---

kind: bug
loop: issue-fix
priority: high
depends_on: []
acceptance:
  - リクエストボディの複数行式の改行が保持されること（`line1\nline2` が一行に圧縮されないこと）
  - `formula` の複数行許容について spec 側への追記判断が確定すること（人間ゲート）
