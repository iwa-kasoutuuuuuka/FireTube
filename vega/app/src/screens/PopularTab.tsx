import React, {useCallback, useEffect, useState} from 'react';
import {loadConfig} from '../api/config';
import {getPopularVideos} from '../api/innertube';
import {VideoItem} from '../api/types';
import {VideoGrid} from '../components/VideoGrid';

interface Props {
  onSelect: (item: VideoItem) => void;
  /** ヘッダーの「更新」が押されるたびに増える */
  reloadKey: number;
}

/** 人気の動画 (ジャンル横断の「今週 × 視聴回数順」) */
export const PopularTab = ({onSelect, reloadKey}: Props) => {
  const [items, setItems] = useState<VideoItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const config = await loadConfig();
      setItems(await getPopularVideos(config.popularQueries));
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
