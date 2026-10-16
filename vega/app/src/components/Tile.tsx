import React from 'react';
import {
  Image,
  ImageSourcePropType,
  StyleSheet,
  Text,
  Pressable,
  View,
} from 'react-native';

export interface TileProps {
  label: string;
  icon: ImageSourcePropType;
  isFocused: boolean;
  onFocus: () => void;
  onBlur?: () => void;
  testID?: string;
  accessibilityLabel?: string;
  hasTVPreferredFocus?: boolean;
}

export const Tile = ({
  label,
  icon,
  isFocused,
  onFocus,
  onBlur,
  testID,
  accessibilityLabel,
  hasTVPreferredFocus,
}: TileProps) => {
  return (
    <Pressable
      style={[styles.tile, isFocused ? styles.focused : styles.default]}
      onFocus={onFocus}
      onBlur={onBlur}
      testID={testID}
      accessibilityLabel={accessibilityLabel}
      accessibilityRole="button"
      hasTVPreferredFocus={hasTVPreferredFocus}>
      <View style={styles.topHalf}>
        <Image
          source={icon}
          style={styles.icon}
          resizeMode="contain"
          accessible={false}
        />
      </View>
      <View style={styles.bottomHalf}>
        <Text style={styles.label}>{label}</Text>
      </View>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  tile: {
    width: 160,
    height: 160,
    borderRadius: 20,
    overflow: 'hidden',
    padding: 12,
  },
  topHalf: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bottomHalf: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  default: {
    backgroundColor: '#0074B8',
  },
  focused: {
    backgroundColor: '#FF6200',
    transform: [{scale: 1.1}],
    opacity: 1,
  },
  icon: {
    width: 40,
    height: 40,
    tintColor: '#FFFFFF',
  },
  label: {
    color: '#FFFFFF',
    fontSize: 22,
    fontWeight: 'bold',
    textAlign: 'center',
    lineHeight: 26,
    includeFontPadding: false,
  },
});
