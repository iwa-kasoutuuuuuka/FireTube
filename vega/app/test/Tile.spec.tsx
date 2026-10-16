import 'react-native';
import {render} from '@testing-library/react-native';
import * as React from 'react';

import {Tile} from '../src/components/Tile';

const defaultProps = {
  label: 'Test Tile',
  icon: {uri: 'mock-icon'},
  isFocused: false,
  onFocus: jest.fn(),
  testID: 'test-tile',
};

describe('Tile', () => {
  it('renders with default style', () => {
    const screen = render(<Tile {...defaultProps} />);
    const tile = screen.getByTestId('test-tile');
    const style = Object.assign({}, ...[tile.props.style].flat());
    expect(screen.getByText('Test Tile')).toBeTruthy();
    expect(style.backgroundColor).toBe('#0074B8');
  });

  it('applies focused style', () => {
    const screen = render(<Tile {...defaultProps} isFocused={true} />);
    const tile = screen.getByTestId('test-tile');
    const style = Object.assign({}, ...[tile.props.style].flat());
    expect(style.backgroundColor).toBe('#FF6200');
  });
});
