import 'react-native';
import {render} from '@testing-library/react-native';
import * as React from 'react';

import {App} from '../src/App';

describe('App', () => {
  it('matches snapshot', () => {
    const screen = render(<App />);
    expect(screen).toMatchSnapshot();
  });

  it('renders all tiles', () => {
    const screen = render(<App />);
    expect(screen.getByTestId('tile-home')).toBeTruthy();
    expect(screen.getByTestId('tile-get-started')).toBeTruthy();
    expect(screen.getByTestId('tile-debug')).toBeTruthy();
    expect(screen.getByTestId('tile-learn-more')).toBeTruthy();
  });
});
