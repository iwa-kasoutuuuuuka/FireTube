import React, {useCallback, useEffect, useState} from 'react';
import {getConfig} from '../api/config';
import {getPopularVideos} from '../api/innertube';
import {VideoItem} from '../api/types';
import {VideoGrid} from '../components/VideoGrid';
import {getCachedList, putCachedList} from '../store/listCache';

interface Props {
  onSelect: (item: VideoItem) => void;
  /** ヘッダーの「更新」が押されるたびに増える */
  reloadKey: number;
}

const CACHE_NAME = 'popular';

/**
 * 人気の動画 (ジャンル横断の「今週 × 視聴回数順」)。
 * 前回の一覧を先に出し、取り直した一覧に差し替える (失敗したら前回の一覧のまま)
 */
export const PopularTab = ({onSelect, reloadKey}: Props) => {
  const [items, setItems] = useState<VideoItem[]>(() =>
    getCachedList(CACHE_NAME),
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      // リモート設定は起動時に裏で読み込み済み (未取得なら組み込みの既定値)。ここでは待たない
      const fresh = await getPopularVideos(getConfig().popularQueries);
      setItems(fresh);
      putCachedList(CACHE_NAME, fresh);
    } catch (e) {
      setError(`読み込みに失敗しました: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load, reloadKey]);

  return (
    <VideoGrid
      items={items}
      loading={loading}
      error={error}
      onSelect={onSelect}
      onRetry={load}
      focusFirst
    />
  );
};
