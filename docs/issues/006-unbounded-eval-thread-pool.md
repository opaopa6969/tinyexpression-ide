# Issue 006: 評価スレッドプールが無制限 (medium)

## 根拠
`src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java:58`
```java
private final ExecutorService evalPool = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "eval-worker");
    t.setDaemon(true);
    return t;
});
```
`newCachedThreadPool` は上限なしでスレッドを生成する。同時リクエスト過多でスレッドが無制限に増加し、JVM がスレッド数上限やメモリ上限に達する可能性がある。5秒タイムアウト後もスレッド終了を待たない。

## 影響範囲
- `src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java`
- 悪意のあるクライアント、または誤動作するクライアントが同時リクエストを大量に送るとDoS状態

## 改善案
- `newFixedThreadPool(N)` で上限を設定
- または `ThreadPoolExecutor` で上限・キュー上限・拒否ポリシー（`CallerRunsPolicy` または 429 応答）を設定
- ローカル IDE 想定なら `newFixedThreadPool(4〜8)` 程度が妥当

## 判断待ち
- 上限値はローカル利用か公開利用かで変わる。ローカル IDE 想定なら小さくてよい
- 公開時（Issue 009 の CDN 解消後など）は再検討

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。
