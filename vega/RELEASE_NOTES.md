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
- 人気の動画 (ジャンル横断の「今週 × 視聴回数順」)
- 検索
- 再生 (分離 DASH を MSE で再生、失敗時は HLS)。決定 = 一時停止、左右 = 10 秒、早送り/巻き戻し = 30 秒
- SponsorBlock の自動スキップ

### まだ無いもの
登録チャンネル・履歴・後で見る・字幕・画質切替・設定画面 (Android 版にある機能は順次移植予定)
