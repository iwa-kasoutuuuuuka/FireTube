# 再生・移植リファレンス

## 再生スタック (公式概要)
Vega Media Player は **W3C Media API** を React Native に提供する: HTMLMediaElement / MSE (適応ストリーミング) / EME (DRM)。
下層は GStreamer によるハードウェアデコード。<video> と MSE の知識がそのまま使える。
出典: https://developer.amazon.com/docs/vega/0.24/media-player.html (SDK の版に合わせて読み替え)

## 未検証事項 (実機で最初に確かめる)
- 対応コーデックの実際 (AVC / VP9 / AV1 / Opus 等)。`MediaSource.isTypeSupported()` と `HTMLMediaElement.canPlayType()` で網羅調査し結果を記録する
- YouTube の分離 DASH (映像 + 音声) を MSE の 2 つの SourceBuffer に追加できるか、シーク・画質切替時の挙動
- googlevideo.com への直接 fetch の可否 (ヘッダ・Range・スロットリング回避)
- 字幕表示手段 (TextTrack か自前描画か)
- ローカル保存 (履歴・後で見る・再生位置) の推奨ストレージ
- Appstore 審査と、YouTube 非公式クライアントの規約上の扱い (配布前に必ず確認)

## 移植の優先順
1. 設定値・モデル (VideoItem など) を TS 型に起こす
2. InnerTube / Piped クライアント (Android 版 `data/innertube`, `data/piped` が仕様の参考)
3. SponsorBlock・Return YouTube Dislike (HTTP のみ)
4. 一覧 UI → 再生画面 → 検索 → 設定
5. 履歴・後で見る・レジューム

検証結果は `vega/docs/` に追記し、確定した事実だけ SKILL.md の「検証済み」に昇格させる。
