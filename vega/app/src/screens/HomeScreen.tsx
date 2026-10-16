import React, {useCallback, useEffect, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {loadConfig} from '../api/config';
import {getPopularVideos} from '../api/innertube';
import {VideoItem} from '../api/types';
import {FocusButton} from '../components/FocusButton';
import {VideoGrid} from '../components/VideoGrid';
import {colors, s} from '../theme';

interface Props {
  onSelect: (item: VideoItem) => void;
  onSearch: () => void;
}

export const HomeScreen = ({onSelect, onSearch}: Props) => {
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
  }, [load]);

  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <Text style={styles.logo}>FireTube</Text>
        <FocusButton label="検索" onPress={onSearch} />
        <FocusButton label="更新" onPress={load} />
        <Text style={styles.section}>人気の動画</Text>
      </View>
      <VideoGrid
        items={items}
        loading={loading}
        error={error}
        onSelect={onSelect}
        onRetry={load}
        focusFirst
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
  logo: {
    color: colors.accent,
    fontSize: s(48),
    fontWeight: 'bold',
    marginRight: s(48),
  },
  section: {color: colors.textDim, fontSize: s(28), marginLeft: s(24)},
});
