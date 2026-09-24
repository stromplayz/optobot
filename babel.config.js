module.exports = {
  presets: [
    ['module:@react-native/babel-preset', { enableBabelRuntime: false }],
    [
      'module-resolver',
      {
        root: ['.'],
        alias: {
          '@': './src',
        },
      },
    ],
  ],
  plugins: [
    // Strip console.* in production (defense in depth on top of ProGuard)
    ...(process.env.NODE_ENV === 'production'
      ? [['transform-remove-console', { exclude: ['error', 'warn'] }]]
      : []),
  ],
}
