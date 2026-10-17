const EtherDrop = process.env.EXPO_OS === 'web'
  ? require('./etherdrop-module.web.js').default
  : require('./etherdrop-module.js').default;

export default EtherDrop;
