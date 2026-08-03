# Issue 004: ExecutionException.getCause() の NPE リスク (medium)

## 根拠
`src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java:116-117`
```java
} catch (ExecutionException e) {
    result.addProperty("error", "Evaluation error: " + e.getCause().getMessage());
```
`e.getCause()` は null を返し得る（`Future` が `ExecutionException` を投ぐとき cause は通常非nullだが、仕様上は null チェックが無い）。cause が null の場合、catch ブロック内で NPE が発生し、エラーレスポンス自体が返せなくなる。

## 影響範囲
- `src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java`
- 評価スレッドで `null` cause の `ExecutionException` が投げられた場合、クライアントに 500 相当の応答欠落

## 改善案
```java
} catch (ExecutionException e) {
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    result.addProperty("error", "Evaluation error: " + cause.getMessage());
```
- null チェックを追加し、cause が無ければ `e` 自体のメッセージを使う

## 判断待ち
- 修正は単純。ただし「仕様違反ではない」「実害は cause=null が発生する経路次第」のため、優先度は medium

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。
- cause=null が実際に発生する経路は未確認（推測を含む）だが、null チェック不足自体は事実。
