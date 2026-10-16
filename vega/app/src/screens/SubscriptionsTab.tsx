import React, {useCallback, useEffect, useState} from 'react';
import {ScrollView, StyleSheet, Text, View} from 'react-native';
import {getChannelVideos, interleave} from '../api/innertube';
import {VideoItem} from '../api/types';
import {FocusButton} from '../components/FocusButton';
import {VideoGrid} from '../components/VideoGrid';
import {Channel, subscriptions, useStore} from '../store/library';
import {colors, s} from '../theme';

interface Props {
  onSelect: (item: VideoItem) => void;
  onOpenChannel: (channel: Channel) => void;
  visible: boolean;
  reloadKey: number;
}

/** 一度に問い合わせるチャンネル数 (Fire TV Stick の通信・メモリへの負荷を抑える) */
const CONCURRENCY = 4;
const PER_CHANNEL = 12;

async function mapLimit<T, R>(
  list: T[],
  limit: number,
  fn: (item: T) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(list.length);
  let next = 0;
  const worker = async () => {
    while (next < list.length) {
      const i = next++;
      results[i] = await fn(list[i]);
    }
  };
  await Promise.all(Array.from({length: Math.min(limit, list.length)}, worker));
  return results;
}

/** 登録チャンネル: チャンネルの一覧と、各チャンネルの新着を交互に並べたフィード */
export const SubscriptionsTab = ({
  onSelect,
  onOpenChannel,
  visible,
  reloadKey,
}: Props) => {
  const channels = useStore(subscriptions);
  const [items, setItems] = useState<VideoItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [loadedFor, setLoadedFor] = useState<string>();

  const key = channels.map((c) => c.id).join(',');

  const load = useCallback(async () => {
    if (channels.length === 0) {
      setItems([]);
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      const lists = await mapLimit(channels, CONCURRENCY, (c) =>
        getChannelVideos(c.id, c.name)
          .then((v) => v.slice(0, PER_CHANNEL))
          .catch(() => [] as VideoItem[]),
      );
      const feed = interleave(lists);
      setItems(feed);
      if (feed.length === 0) {
        setError('新着動画を取得できませんでした');
      }
    } finally {
      setLoading(false);
    }
  }, [channels]);

  // 表示されたとき、登録内容が変わっていたら読み直す
  useEffect(() => {
    if (visible && loadedFor !== `${key}#${reloadKey}`) {
      setLoadedFor(`${key}#${reloadKey}`);
      load();
    }
  }, [visible, key, reloadKey, loadedFor, load]);

  if (channels.length === 0) {
    return (
      <View style={styles.empty}>
        <Text style={styles.emptyText}>登録チャンネルはまだありません</Text>
        <Text style={styles.hint}>
          再生中に下キーでメニューを開き「チャンネル登録」で追加できます
        </Text>
      </View>
    );
  }

  return (
    <View style={styles.root}>
      <ScrollView
        horizontal
        style={styles.channels}
        contentContainerStyle={styles.channelsContent}>
        {channels.map((c) => (
          <FocusButton
            key={c.id}
            label={c.name}
            onPress={() => onOpenChannel(c)}
          />
        ))}
      </ScrollView>
      <VideoGrid
        items={items}
        loading={loading}
        error={error}
        onSelect={onSelect}
        onRetry={load}
      />
    </View>
  );
};

const styles = StyleSheet.create({
  root: {flex: 1},
  channels: {flexGrow: 0},
  channelsContent: {paddingHorizontal: s(60), paddingVertical: s(8)},
  empty: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  emptyText: {color: colors.text, fontSize: s(32)},
  hint: {color: colors.textDim, fontSize: s(24), marginTop: s(16)},
});
