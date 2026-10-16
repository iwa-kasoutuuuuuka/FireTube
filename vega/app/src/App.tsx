import React, {useState} from 'react';
import {StyleSheet, Text, ImageBackground, View, Image} from 'react-native';
import {TVFocusGuideView} from '@amazon-devices/react-native-kepler';
import {Tile} from './components/Tile';
import {tiles} from './data/tiles';

export const App = () => {
  const [focusedTileId, setFocusedTileId] = useState<string>('home');

  const focusedTile = tiles.find((t) => t.id === focusedTileId);

  return (
    <ImageBackground
      source={require('./assets/background.png')}
      style={styles.background}>
      <View style={styles.headerArea}>
        {focusedTileId === 'home' ? (
          <>
            <View style={styles.headerTextContainer}>
              <Text style={styles.headerTitle}>Hello Vega,</Text>
              <Text style={styles.headerSubtitle}>
                Use your remote to explore and start your Vega journey 🚀
              </Text>
            </View>
            <Image
              source={require('./assets/vega.png')}
              style={styles.vegaLogo}
              resizeMode="contain"
              testID="vega-logo"
            />
          </>
        ) : (
          <>
            <View style={styles.headerTextContainer}>
              <Text style={styles.focusedTitle}>{focusedTile?.label}</Text>
            </View>
            {focusedTile?.description && (
              <Text style={styles.focusedDescription}>
                {focusedTile.description}
              </Text>
            )}
          </>
        )}
      </View>

      <TVFocusGuideView style={styles.tileRowContent}>
        {tiles.map((tile) => (
          <Tile
            key={tile.id}
            label={tile.label}
            icon={tile.icon}
            isFocused={focusedTileId === tile.id}
            onFocus={() => setFocusedTileId(tile.id)}
            onBlur={() => {}}
            testID={`tile-${tile.id}`}
            accessibilityLabel={tile.accessibilityLabel}
            hasTVPreferredFocus={tile.id === 'home'}
          />
        ))}
      </TVFocusGuideView>
    </ImageBackground>
  );
};

const styles = StyleSheet.create({
  background: {
    flex: 1,
    paddingHorizontal: 64,
    paddingVertical: 32,
  },
  headerArea: {
    flex: 2,
    flexDirection: 'row',
    alignItems: 'center',
  },
  headerTextContainer: {
    flex: 1,
    flexShrink: 1,
  },
  headerTitle: {
    color: '#FFFFFF',
    fontSize: 64,
    lineHeight: 72,
    fontWeight: '600',
    flexShrink: 1,
  },
  headerSubtitle: {
    color: '#FFFFFF',
    fontSize: 24,
    flexShrink: 1,
  },
  vegaLogo: {
    width: 280,
    height: 200,
    marginLeft: 40,
  },
  focusedTitle: {
    color: '#FFFFFF',
    fontSize: 64,
    lineHeight: 72,
    fontWeight: 'bold',
    flexShrink: 1,
  },
  focusedDescription: {
    color: '#FFFFFF',
    fontSize: 24,
    lineHeight: 32,
    flex: 1,
    marginLeft: 20,
    paddingTop: 10,
  },
  tileRowContent: {
    flex: 2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
});
