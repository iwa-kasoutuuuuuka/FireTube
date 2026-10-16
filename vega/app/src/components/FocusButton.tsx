import React, {useState} from 'react';
import {Pressable, StyleSheet, Text} from 'react-native';
import {colors, s} from '../theme';

interface Props {
  label: string;
  onPress: () => void;
  hasTVPreferredFocus?: boolean;
}

/** リモコン操作用のボタン。フォーカス時に枠を出す */
export const FocusButton = ({label, onPress, hasTVPreferredFocus}: Props) => {
  const [focused, setFocused] = useState(false);
  return (
    <Pressable
      style={[styles.button, focused && styles.focused]}
      onFocus={() => setFocused(true)}
      onBlur={() => setFocused(false)}
      onPress={onPress}
      hasTVPreferredFocus={hasTVPreferredFocus}
      accessibilityRole="button"
      accessibilityLabel={label}>
      <Text style={styles.text}>{label}</Text>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  button: {
    paddingHorizontal: s(36),
    paddingVertical: s(14),
    marginRight: s(16),
    borderRadius: s(40),
    backgroundColor: colors.surface,
    borderWidth: s(4),
    borderColor: 'transparent',
  },
  focused: {borderColor: colors.text, backgroundColor: colors.surfaceFocused},
  text: {color: colors.text, fontSize: s(28)},
});
