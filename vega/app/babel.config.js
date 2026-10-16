module.exports = {
  presets: [
    ['module:metro-react-native-babel-preset', {useTransformReactJSXExperimental: true}],
  ],
  // react-native-w3cmedia の README の指定 (無いと実行時に React の ReferenceError になる)
  plugins: [['@babel/plugin-transform-react-jsx', {runtime: 'automatic'}]],
};
