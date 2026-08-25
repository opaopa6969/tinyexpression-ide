---
name: formula-eval-workflow
description: 式に含まれる $variableName を自動検出し、変数マップを組み立てて evaluate に渡す手順
volta:
  version: 1
  namespace: tinyexpression-ide
  locality: repo
  applies_when:
    - goal:
        eq: "evaluate tinyexpression formula"
    - has_file: src/main/java/org/unlaxer/tinyexpression/ide
  requires:
    tools:
      - tinyexpression-ide__evaluate
  min_role: MEMBER
  export: allowed
---

# 式評価ワークフロー

## 前提

- `tinyexpression-ide__evaluate` が使える状態であること（`catalog__backend_status` で `tinyexpression-ide` が `ready`）

## 手順

1. 評価したい TinyExpression 式を用意する（例: `1 + $x * 2 + $y`）
2. 式から `$変数名` を全て抽出する
   - 正規表現: `\$([A-Za-z][A-Za-z0-9_]*)`
   - 例: `1 + $x * 2 + $y` → `["x", "y"]`
3. 各変数の値を文字列で用意する（例: `{"x": "10", "y": "5"}`）
4. `tinyexpression-ide__evaluate` を呼ぶ:
   ```json
   {"formula": "1 + $x * 2 + $y", "variables": {"x": "10", "y": "5"}}
   ```
5. 結果の `result` が評価値、`error` が null なら成功
6. `error` が非 null なら式を修正して再評価
7. 事前に `tinyexpression-ide__validate` で構文チェック可能

## 注意

- 現状の evaluate は四則演算のみ（MVP 簡易版 `SimpleExpressionEvaluator`）
- 本命の TinyExpression 評価（if/match/string/boolean 等）は `tinyexpr__evaluate` を使う
- `resultType` パラメータは現状未実装（無視される）

## 組み合わせ

```
tinyexpression-ide__validate → tinyexpression-ide__evaluate
```

`validate` で構文エラーを検出してから `evaluate` で評価する。エラーがあれば式を修正して再試行。
