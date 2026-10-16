import React, {useState} from 'react';
import {Pressable, StyleSheet, Text} from 'react-native';
import {colors, s} from '../theme';

interface Props {
  label: string;
  onPress: () => void;
  hasTVPreferredFocus?: boolean;
  /** 選択中 (タブなど) の表示 */
  selected?: boolean;
  onFocus?: () => void;
}

/** リモコン操作用のボタン。フォーカス時に枠を出す */
export const FocusButton = ({
  label,
  onPress,
  hasTVPreferredFocus,
  selected,
  onFocus,
}: Props) => {
  const [focused, setFocused] = useState(false);
  return (
    <Pressable
      style={[
        styles.button,
        selected && styles.selected,
        focused && styles.focused,
      ]}
      onFocus={() => {
        setFocused(true);
        onFocus?.();
      }}
      onBlur={() => setFocused(false)}
      onPress={onPress}
      hasTVPreferredFocus={hasTVPreferredFocus}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{selected}}>
      <Text style={[styles.text, selected && styles.selectedText]}>
        {label}
      </Text>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  button: {
    paddingHorizontal: s(36),
    paddingVertical: s(14),
    marginRight: s(16),
    marginBottom: s(8),
    borderRadius: s(40),
    backgroundColor: colors.surface,
    borderWidth: s(4),
    borderColor: 'transparent',
  },
  selected: {backgroundColor: colors.text},
  focused: {borderColor: colors.accent, backgroundColor: colors.surfaceFocused},
  text: {color: colors.text, fontSize: s(28)},
  selectedText: {color: colors.background, fontWeight: 'bold'},
});
