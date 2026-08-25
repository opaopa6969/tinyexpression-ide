# Issue 007: テスト0件・CI設定なし (medium)

## 根拠
- `glob src/test/**/*` → No files found（テストクラス不在）
- `glob .github/**/*` → No files found（CI設定なし）
- `pom.xml:81-82` で `junit:junit` が test scope で定義済み（JUnit 4.13.2）
- `src/test/java/` は空

## 影響範囲
- `src/test/`, `.github/workflows/`, `pom.xml`
- リファクタリング・仕様変更時に回帰検出不能
- 市場調査 `docs/market/2026-07-31.md` の提案1（実ランタイム接続）の前提として、現状の評価器の振る舞いを固定するテストが無い

## 改善案
- `SimpleExpressionEvaluator` の単体テストを最小追加（四則演算・括弧・単項マイナス・ゼロ除算・`true/false` 変換）
- `EvalEndpoint` の結合テスト（変数置換・400・タイムアウト）
- GitHub Actions で `mvn test` を実行するワークフロー追加
- JUnit 4 を使うか JUnit 5 に移行するかは判断点

## 判断待ち (人間ゲート: 広い影響範囲)
- テスト範囲（最小限か網羅的か）と CI の実行環境（OS・JDK 版・キャッシュ）は判断が必要
- JUnit 4 → 5 移行を含めるか

## 重複・推測チェック
- 根拠あり、事実。重複なし。
- READMEロードマップとの重複: ロードマップ#1/#2（実ランタイム接続）の「前提」としては言及可能だが、テスト不在自体は独立した問題。

---

kind: test
loop: test-gap
priority: medium
depends_on: []
acceptance:
  - テスト範囲（最小限/網羅的）とCI実行環境（OS・JDK・キャッシュ）が判断確定すること（人間ゲート）
  - `SimpleExpressionEvaluator` の単体テストと `EvalEndpoint` の結合テストが追加されること
  - CI で `mvn test` が実行されること
