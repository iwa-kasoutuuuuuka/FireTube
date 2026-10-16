import React, {useState} from 'react';
import {Image, Pressable, StyleSheet, Text, View} from 'react-native';
import {VideoItem} from '../api/types';
import {colors, formatDuration, s} from '../theme';

interface Props {
  item: VideoItem;
  onPress: (item: VideoItem) => void;
  hasTVPreferredFocus?: boolean;
}

export const CARD_WIDTH = s(400);

export const VideoCard = ({item, onPress, hasTVPreferredFocus}: Props) => {
  const [focused, setFocused] = useState(false);
  return (
    <Pressable
      style={[styles.card, focused && styles.focused]}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onPress={() => onPress(item)}
      hasTVPreferredFocus={hasTVPreferredFocus}
      accessibilityRole="button"
      accessibilityLabel={item.title}>
      <View>
        <Image source={{uri: item.thumbnailUrl}} style={styles.thumb} resizeMode="cover" />
        {item.durationSeconds > 0 && (
          <Text style={styles.duration}>{formatDuration(item.durationSeconds)}</Text>
        )}
      </View>
      <Text style={styles.title} numberOfLines={2}>
        {item.title}
      </Text>
      <Text style={styles.uploader} numberOfLines={1}>
        {item.uploaderName}
      </Text>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  card: {
    width: CARD_WIDTH,
    margin: s(12),
    padding: s(8),
    borderRadius: s(12),
    borderWidth: s(4),
    borderColor: 'transparent',
    backgroundColor: colors.surface,
  },
  focused: {
    borderColor: colors.text,
    backgroundColor: colors.surfaceFocused,
    transform: [{scale: 1.05}],
  },
  thumb: {
    width: '100%',
    aspectRatio: 16 / 9,
    borderRadius: s(8),
    backgroundColor: '#000',
  },
  duration: {
    position: 'absolute',
    right: s(8),
    bottom: s(8),
    paddingHorizontal: s(8),
    borderRadius: s(4),
    backgroundColor: 'rgba(0,0,0,0.8)',
    color: colors.text,
    fontSize: s(20),
  },
  title: {
    color: colors.text,
    fontSize: s(24),
    lineHeight: s(32),
    marginTop: s(8),
    height: s(64),
  },
  uploader: {
    color: colors.textDim,
    fontSize: s(20),
    marginTop: s(4),
  },
});
