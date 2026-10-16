import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {colors, s} from '../theme';
import {FocusButton} from './FocusButton';

export interface MenuItem {
  label: string;
  onPress: () => void;
}

/** 再生中に下キーで開くメニュー。上下で選び、決定で実行、戻るで閉じる */
export const PlayerMenu = ({items}: {items: MenuItem[]}) => (
  <View style={styles.panel}>
    <Text style={styles.title}>メニュー</Text>
    {items.map((item, i) => (
      <FocusButton
        key={i}
        label={item.label}
        onPress={item.onPress}
        hasTVPreferredFocus={i === 0}
      />
    ))}
    <Text style={styles.hint}>戻る: 閉じる</Text>
  </View>
);

const styles = StyleSheet.create({
  panel: {
    position: 'absolute',
    top: s(60),
    right: s(60),
    width: s(620),
    padding: s(28),
    borderRadius: s(16),
    backgroundColor: 'rgba(15,15,15,0.92)',
  },
  title: {
    color: colors.textDim,
    fontSize: s(24),
    marginBottom: s(16),
  },
  hint: {color: colors.textDim, fontSize: s(20), marginTop: s(12)},
});
