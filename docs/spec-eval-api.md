# `/api/eval` 外部契約（MVP）

## 目的

TinyExpression の式と変数を受け取り、現在の MVP 評価器で試行した結果を返す。
本番の TinyExpression ランタイムと同じ意味で評価できることは、現時点では保証しない。

## 入力

`POST /api/eval` に JSON を送る。

- `formula`: 必須の文字列。空または空白だけの場合は HTTP 400。
- `variables`: 任意の JSON オブジェクト。値は評価前に文字列として扱われる。
- `resultType`: 任意の文字列。受け付けられる値の意味と、結果への反映方法は未確定。
  TODO: 要確認（このキーを MVP で実装するのか、将来のランタイム接続まで保留するのか不明）

## 成功時の出力

HTTP 200 で JSON を返す。少なくとも次のキーを含む。

- `result`: 評価結果の文字列表現
- `error`: `null`
- `formula`: 入力された式
- `substituted`: `$名前` を変数値で置換した式

## 評価できない場合

HTTP 200 の JSON に `result: null`、`error` にエラーメッセージ、`formula` に入力式を返す。
空の式だけは HTTP 400 で、`error` に `Missing 'formula' field` を返す。

評価処理には 5 秒のタイムアウトがある。タイムアウト時は `result: null` とし、`error` に
タイムアウトメッセージを返す。

## 壊してはいけない契約

- パスは `/api/eval`、メソッドは `POST` のままにする。
- ブラウザ UI は `formula`、`variables`、`resultType: "number"` を送る。
- 成功・評価失敗のどちらでも JSON を返す。

## 未確認事項

- `resultType` の仕様と、`number` 以外を指定した場合の期待結果は不明。
- 完全な TinyExpression の文法・型・エラー位置をこの API が保証するかは不明。
