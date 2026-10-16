FireTube の Vega OS 版 (Fire TV Stick 4K Select / 4K 第3世代 / HD 2026年モデルなど) の**試作版**です。

> ⚠️ 実機での動作はまだ確認していません。特に動画の再生は端末によって動かない可能性があります。

### インストール
Vega OS は APK のようにファイルを開いて入れることができません。**開発者モード + Vega CLI** で入れます。
手順: [vega/INSTALL_VEGA.txt](https://github.com/iwa-kasoutuuuuuka/FireTube/blob/main/vega/INSTALL_VEGA.txt)

```bash
vega device install-app --packagePath FireTube-Vega-<版>-armv7.vpkg
vega device launch-app --appName com.firetube.tv.vega.main
```

Fire TV Stick 実機には **armv7** の .vpkg を使ってください (x86_64 / aarch64 は Virtual Device 用)。

### できること
- トップ画面のタブ: ホーム (人気の動画) / 登録チャンネル / 後で見る / 履歴 / 検索 / 設定
- 再生 (分離 DASH を MSE で再生、失敗時は HLS)。決定 = 一時停止、左右 = 10 秒、早送り/巻き戻し = 30 秒
- 再生中に **下キーでメニュー**: 後で見る / 字幕 / 画質 / チャンネル登録 / チャンネルを開く
- 字幕 (日本語が無ければ自動翻訳も選べる)、画質切替 (1080p〜144p)
- 続きから再生、視聴履歴、登録チャンネルの新着フィード
- SponsorBlock の自動スキップ
- 設定: 最大画質 / 字幕の既定 / SponsorBlock / 続きから再生 / 履歴の保存・消去

データ (履歴・後で見る・登録チャンネル・設定) は端末内にのみ保存されます。

### 変更点 (v0.3.0) — 高速化
- 一覧のサムネイルを小さい画像 (約 220KB → 約 18KB) にして、通信量・メモリを削減
- 人気の動画・登録チャンネルの新着を端末に保存し、起動直後は前回の一覧をすぐ表示
- 再生に必要な visitorData を起動後に先読みして端末に保存 (初回再生の待ち時間を約 1 秒短縮)
- 一覧のカードが不要に描き直されないように改善

### 変更点 (v0.2.0)
- 登録チャンネル・履歴・後で見る・字幕・画質切替・設定画面・続きから再生を追加
