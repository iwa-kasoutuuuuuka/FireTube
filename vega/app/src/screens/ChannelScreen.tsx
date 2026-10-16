import React, {useCallback, useEffect, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {getChannelVideos} from '../api/innertube';
import {VideoItem} from '../api/types';
import {FocusButton} from '../components/FocusButton';
import {VideoGrid} from '../components/VideoGrid';
import {
  Channel,
  subscriptions,
  toggleSubscription,
  useStore,
} from '../store/library';
import {colors, s} from '../theme';

interface Props {
  channel: Channel;
  onSelect: (item: VideoItem) => void;
}

/** チャンネルの新着動画と、登録 / 登録解除 */
export const ChannelScreen = ({channel, onSelect}: Props) => {
  const subscribed = useStore(subscriptions).some((c) => c.id === channel.id);
  const [items, setItems] = useState<VideoItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setItems(await getChannelVideos(channel.id, channel.name));
    } catch (e) {
      setError(`読み込みに失敗しました: ${(e as Error).message}`);
    } finally {
      setLoading(false);
    }
  }, [channel.id, channel.name]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <Text style={styles.name} numberOfLines={1}>
          {channel.name}
        </Text>
        <FocusButton
          label={subscribed ? '登録済み (解除)' : 'チャンネル登録'}
          selected={subscribed}
          onPress={() => toggleSubscription(channel)}
          hasTVPreferredFocus
        />
      </View>
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
  root: {flex: 1, backgroundColor: colors.background},
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: s(60),
    paddingTop: s(40),
    paddingBottom: s(16),
  },
  name: {
    color: colors.text,
    fontSize: s(44),
    fontWeight: 'bold',
    marginRight: s(40),
    flexShrink: 1,
  },
});
