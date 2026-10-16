#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""上流サービス (Piped / YouTube InnerTube) の疎通チェック

remote-config.json に書かれた内容が現在も機能しているかを確認する。
アプリのコードでは直せない「上流の変化」を、利用者より先に検知するためのもの。

  python scripts/check_upstream.py [remote-config.json のパス]

終了コード: 0 = 正常 (警告のみを含む) / 1 = 対応が必要
- エラー: 設定が不正 / 全 Piped インスタンスでトレンド取得不可 / 「人気の動画」検索が空
- 警告  : 一部の Piped インスタンスのみ不調
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

TIMEOUT = 20
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
SAMPLE_VIDEO_ID = "dQw4w9WgXcQ"
# アプリ側 (InnerTubeClient) と同じ値
SEARCH_FILTER_POPULAR_THIS_WEEK = "CAMSBAgDEAE="
WEB_CLIENT_VERSION = "2.20240401.01.00"
KNOWN_PLAYER_CLIENTS = {"IOS_KIDS", "VISIONOS", "IOS_EMBEDDED", "MWEB", "ANDROID_KIDS", "IOS"}
VERSION_KEYS = {"WEB", "IOS", "IOS_KIDS", "ANDROID_KIDS", "MWEB", "VISIONOS"}
BASE_URL_REGEX = re.compile(r"^https://[A-Za-z0-9.-]+(:\d{1,5})?(/[A-Za-z0-9._/-]*)?$")
VERSION_REGEX = re.compile(r"^[0-9][0-9.]{1,23}$")
MIN_POPULAR_PER_QUERY = 5

errors = []
warnings = []
lines = []


def log(line):
    lines.append(line)
    print(line, flush=True)


def error(message):
    errors.append(message)
    log(f"::error::{message}")


def warning(message):
    warnings.append(message)
    log(f"::warning::{message}")


def http_json(url, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"User-Agent": USER_AGENT}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
        return json.loads(response.read().decode("utf-8"))


def string_list(config, key):
    value = config.get(key)
    if not isinstance(value, list) or not all(isinstance(x, str) and x.strip() for x in value):
        error(f"remote-config: '{key}' は空でない文字列の配列である必要があります")
        return []
    return value


def check_config(config):
    """アプリ側 RemoteConfig.parse と同じ規則で検証する (不正な項目はアプリでは無視され既定値になる)"""
    instances = string_list(config, "pipedInstances")
    for url in instances:
        if not BASE_URL_REGEX.match(url.rstrip("/")):
            error(f"remote-config: pipedInstances の URL が不正です (https のみ有効): {url}")

    race = string_list(config, "playerRaceClients") if config.get("playerRaceClients") else []
    fallback = string_list(config, "playerFallbackClients") if config.get("playerFallbackClients") else []
    for name in race + fallback:
        if name not in KNOWN_PLAYER_CLIENTS:
            error(f"remote-config: 未知の再生クライアント名です: {name}")
    if not race and not fallback:
        error("remote-config: 再生クライアントが 1 件も指定されていません")

    queries = string_list(config, "popularQueries")

    versions = config.get("clientVersions", {})
    if not isinstance(versions, dict):
        error("remote-config: 'clientVersions' はオブジェクトである必要があります")
    else:
        for name, version in versions.items():
            if name not in VERSION_KEYS:
                error(f"remote-config: clientVersions に未知のクライアント名があります: {name}")
            elif not isinstance(version, str) or not VERSION_REGEX.match(version):
                error(f"remote-config: clientVersions['{name}'] は数字とドットのみで指定してください: {version!r}")
    return instances, queries, versions if isinstance(versions, dict) else {}


def check_piped(instances):
    trending_ok = 0
    for base in instances:
        base = base.rstrip("/")
        try:
            trending = http_json(f"{base}/trending?region=JP")
            count = len(trending) if isinstance(trending, list) else 0
        except Exception as e:  # noqa: BLE001 - 失敗理由をそのまま報告する
            warning(f"Piped {base}: トレンド取得に失敗 ({type(e).__name__}: {e})")
            continue
        if count == 0:
            warning(f"Piped {base}: トレンドが 0 件")
            continue
        trending_ok += 1

        try:
            streams = http_json(f"{base}/streams/{SAMPLE_VIDEO_ID}")
            videos = [
                v for v in streams.get("videoStreams", [])
                if str(v.get("url", "")).startswith("http") and re.search(r"\d", str(v.get("quality", "")))
            ]
            playable = bool(videos) or bool(streams.get("hls"))
        except Exception as e:  # noqa: BLE001
            playable = False
            warning(f"Piped {base}: ストリーム取得に失敗 ({type(e).__name__}: {e})")
        else:
            if not playable:
                warning(f"Piped {base}: 再生可能なストリームを返しません")
        log(f"Piped {base}: trending={count}, streams={'OK' if playable else 'NG'}")

    if instances and trending_ok == 0:
        error("全ての Piped インスタンスでトレンドを取得できません。remote-config.json の pipedInstances を更新してください")


def count_video_renderers(node):
    if isinstance(node, dict):
        return (1 if "videoRenderer" in node else 0) + sum(count_video_renderers(v) for v in node.values())
    if isinstance(node, list):
        return sum(count_video_renderers(v) for v in node)
    return 0


def check_popular_search(queries, versions):
    total = 0
    for query in queries:
        body = {
            "context": {"client": {
                "hl": "ja", "gl": "JP", "clientName": "WEB",
                "clientVersion": versions.get("WEB", WEB_CLIENT_VERSION), "utcOffsetMinutes": 540,
            }},
            "query": query,
            "params": SEARCH_FILTER_POPULAR_THIS_WEEK,
        }
        try:
            result = http_json("https://www.youtube.com/youtubei/v1/search?prettyPrint=false", body)
            count = count_video_renderers(result)
        except Exception as e:  # noqa: BLE001
            warning(f"InnerTube 検索 '{query}': 失敗 ({type(e).__name__}: {e})")
            continue
        total += count
        log(f"InnerTube 人気検索 '{query}': {count} 件")
        if count < MIN_POPULAR_PER_QUERY:
            warning(f"InnerTube 検索 '{query}': {count} 件しか返りません。クエリの見直しを検討してください")
    if queries and total == 0:
        error("「人気の動画」検索が 1 件も返りません。検索フィルタまたは WEB クライアントが無効化された可能性があります")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "remote-config.json"
    try:
        with open(path, encoding="utf-8") as f:
            config = json.load(f)
        if not isinstance(config, dict):
            raise ValueError("トップレベルがオブジェクトではありません")
    except Exception as e:  # noqa: BLE001
        error(f"remote-config: 読み込めません ({e})")
        config = None

    if config is not None:
        instances, queries, versions = check_config(config)
        check_piped(instances)
        check_popular_search(queries, versions)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## 上流ヘルスチェック\n\n")
            f.write(f"- エラー: {len(errors)} 件 / 警告: {len(warnings)} 件\n\n```\n")
            f.write("\n".join(lines))
            f.write("\n```\n")

    print(f"\nerrors={len(errors)} warnings={len(warnings)}")
    return 1 if errors else 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
