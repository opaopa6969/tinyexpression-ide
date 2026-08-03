# Issue 008: CORS が wildcard (low)

## 根拠
`src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java:67, 135`
```java
resp.setHeader("Access-Control-Allow-Origin", "*");
```
`doPost` と `doOptions` の両方で `Access-Control-Allow-Origin: *` を設定。

## 影響範囲
- `src/main/java/org/unlaxer/tinyexpression/ide/EvalEndpoint.java`
- ローカル IDE として same-origin で動作するため、現状は `*` でも実害なし
- 公開時に任意の origin から `/api/eval` を叩かれるリスク

## 改善案
- 設定可能にする（環境変数または起動引数で許可 origin を指定）
- または same-origin に制限（ヘッダを削除）
- 公開時（READMEロードマップ#6 Electron/Tauri 化など）に合わせて再検討

## 判断待ち
- ローカル IDE 想定か、公開前提かで対応が変わる
- 現状は low severity で、公開前に対応すればよい

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。
