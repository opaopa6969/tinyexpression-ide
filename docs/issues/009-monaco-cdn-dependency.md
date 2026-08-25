# Issue 009: Monaco を CDN から読み込み (low)

## 根拠
`src/main/resources/static/index.html:223, 239`
```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs/loader.min.js"></script>
```
```js
require.config({ paths: { vs: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.45.0/min/vs' } });
```
Monaco Editor の loader と editor.main を cdnjs から読み込んでいる。

## 影響範囲
- `src/main/resources/static/index.html`
- オフライン環境・社内ネットワーク（外部 CDN 不通）でエディタが動作しない
- CDN 障害時も同様
- ローカル IDE としての主要機能が失われる

## 改善案
- Monaco をローカルバンドル（`src/main/resources/static/vendor/monaco/` 等に配置）
- または fallback とロード失敗時のエラー表示を追加
- npm パッケージから取得した monaco-editor をリソースに含めるビルド手順の追加

## 判断待ち
- バンドルサイズ増加（Monaco は数 MB）を許容するか
- ビルド手順を追加するか、単にファイルを配置するか

## 重複・推測チェック
- 行番号根拠あり、事実。重複なし。

---

kind: architecture
loop: architecture-decision
priority: low
depends_on: []
acceptance:
  - Monaco のバンドル方針（ローカル配置 or npm ビルド手順）が判断確定すること（人間ゲート）
  - CDN 依存が解消され、オフライン/外部CDN不通環境でもエディタが動作すること
