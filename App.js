import { useEffect, useState } from 'react';
import { Alert, Animated, BackHandler, Modal, PermissionsAndroid, Platform, Pressable,
  ScrollView, StatusBar, StyleSheet, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import EtherDrop from './modules/etherdrop';
import { Button, Card, Icon, Label, Progress, Title, Toggle, WifiBadge, bytes, colors, duration, styles as s } from './src/ui';

const initialState = { role: '', network: null, error: '', peers: [], transfers: [] };

export default function App() {
  const [state, setState] = useState(initialState);
  const [role, setRole] = useState('');
  const [page, setPage] = useState('home');
  const [selected, setSelected] = useState([]);
  const [selectedItem, setSelectedItem] = useState('');
  const [verify, setVerify] = useState(false);
  const [wrap, setWrap] = useState(true);
  const [activeId, setActiveId] = useState('');
  const [tab, setTab] = useState('overview');
  const [detailsTab, setDetailsTab] = useState('fields');
  const [conflict, setConflict] = useState(null);
  const [picking, setPicking] = useState(false);
  const [opacity] = useState(() => new Animated.Value(1));
  const transfers = state.transfers;
  const busy = transfers.some(item => !item.finished);
  const current = transfers.find(item => item.id === activeId) || transfers[0];

  async function run(action) {
    try { return await action(); }
    catch (error) { Alert.alert('EtherDrop', error.message || String(error)); }
  }
  useEffect(() => {
    function applyState(next) { setState(next); if (next.role) setRole(next.role); }
    const subscription = EtherDrop.addListener('onState', event => applyState(JSON.parse(event.state)));
    const conflictSubscription = EtherDrop.addListener('onConflict', event => setConflict(event.path));
    run(async () => applyState(JSON.parse(await EtherDrop.getState())));
    return () => { subscription.remove(); conflictSubscription.remove(); };
  }, []);
  useEffect(() => {
    if (transfers.length && (page === 'main' || page === 'home')) { setTab('overview'); setPage('transfer'); }
  }, [transfers.length, page]);
  useEffect(() => {
    opacity.setValue(0);
    Animated.timing(opacity, { toValue: 1, duration: 160, useNativeDriver: true }).start();
  }, [page]);

  function back() {
    if (conflict) return true;
    if (page === 'connection' || page === 'options') { setPage(transfers.length ? 'transfer' : 'main'); return true; }
    if (page === 'help') { setPage('home'); return true; }
    if (page === 'transfer') {
      if (busy) Alert.alert('Transfer in progress', 'Use Cancel transfer to stop it, or leave EtherDrop running in the background.');
      else run(async () => { await EtherDrop.dismiss(); setPage('main'); });
      return true;
    }
    if (page === 'main') { run(async () => { await EtherDrop.stop(); setRole(''); setPage('home'); }); return true; }
    return false;
  }
  useEffect(() => { const listener = BackHandler.addEventListener('hardwareBackPress', back); return () => listener.remove(); }, [page, busy, conflict, transfers.length]);

  async function start(nextRole = role) {
    setRole(nextRole);
    setPage('main');
    if (Platform.OS === 'android' && Platform.Version >= 33) await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    await run(() => EtherDrop.start(nextRole));
  }
  async function add(kind) {
    setPicking(true);
    await run(async () => {
      const added = await EtherDrop.pick(kind);
      setSelected(items => Array.from(new Map([...items, ...added].map(item => [item.uri, item])).values()));
    });
    setPicking(false);
  }
  async function accept() {
    setPicking(true);
    await run(async () => {
      const destination = await EtherDrop.pick('destination');
      if (destination.length) await EtherDrop.accept(current.id, destination[0].uri);
      else await EtherDrop.decline(current.id);
    });
    setPicking(false);
  }
  const connected = state.peers.length;
  const summary = state.error ? 'Needs attention' : !state.network ? 'Checking Wi-Fi…' : connected ?
    role === 'sender' ? `${connected} receiver${connected === 1 ? '' : 's'} connected` : `Connected to ${state.peers[0].name}` : 'Searching…';

  function header(title, showBack = true) {
    return <><View style={s.between}>{showBack ? <Button kind="ghost" icon="back" onPress={back}>Back</Button> : <View />}<WifiBadge /></View><Title>{title}</Title></>;
  }
  function connectionState() {
    const title = state.error ? 'Wi-Fi needs attention' : !state.network ? 'Checking Wi-Fi…' : connected ? 'Waiting for files' : `Looking for a ${role === 'sender' ? 'receiver' : 'sender'}…`;
    const message = state.error || (!state.network ? 'Looking for a connected Wi-Fi network.' : connected ?
      'Choose where to save each transfer when it arrives.' : `Open EtherDrop on the other device and choose ${role === 'sender' ? 'Receive' : 'Send'} files.\nUse the same Wi-Fi network and Wi-Fi mode.`);
    return <View style={a.empty}><Icon name={state.error ? 'wifiOff' : connected ? 'download' : 'wifi'} size={48} />
      <Title style={a.centerTitle}>{title}</Title><Label style={a.centerText}>{message}</Label>
      {(state.error || state.network && !connected) && <View style={a.centerActions}>
        <Button icon="refresh" onPress={() => start()}>Rescan</Button><Button kind="ghost" onPress={() => setPage('connection')}>Connection settings</Button>
      </View>}
    </View>;
  }
  function diagnostics(transfer) {
    return {
      'Application': 'EtherDrop Mobile', 'Protocol version': '3', 'Local device': state.network?.device || '—',
      'Transport': 'TCP over local Wi-Fi / IPv4', 'Adapter': state.network?.name || '—',
      'Local address': state.network?.address || '—', 'Discovery endpoint': `${state.network?.broadcast || '—'}:45670/UDP`,
      'Transfer port': '45671/TCP', 'Transfer ID': transfer.id, 'Direction': transfer.direction,
      'Peer device': transfer.peer, 'Peer address': transfer.ip, 'Phase': transfer.phase,
      'File count': transfer.count, 'Total bytes': bytes(transfer.total), 'Transferred': bytes(transfer.done),
      'Current relative path': transfer.current || '—', 'Receiver destination': transfer.destination || 'Not chosen',
      'Integrity verification': transfer.verify ? 'SHA-256 enabled' : 'Disabled',
      'Wrap transfer in a folder': transfer.wrap ? 'Yes' : 'No', 'Block size': '8 MiB',
      'Power behavior': 'Transfers continue in the background while EtherDrop is running', 'Status': transfer.message,
    };
  }
  function transferPage() {
    if (!current) return null;
    const incoming = current.awaitingApproval;
    const percent = current.total ? current.done / current.total * 100 : current.finished && current.phase === 'Completed' ? 100 : 0;
    const fields = diagnostics(current);
    const phaseColor = current.phase === 'Completed' ? colors.success : ['Failed', 'Cancelled', 'Declined'].includes(current.phase) ? colors.danger : current.phase === 'Paused' ? colors.warning : colors.accent;
    return <>
      {header(role === 'sender' ? 'Send files' : 'Receive files', false)}
      {transfers.length > 1 && <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={a.peerTabs}>
        {transfers.map(item => <Pressable key={item.id} accessibilityRole="button" onPress={() => { setActiveId(item.id); setTab('overview'); }}
          style={[a.peerTab, current.id === item.id && a.selectedSegment]}><Text style={s.text}>{item.peer}</Text><Label style={a.small}>{item.phase}</Label></Pressable>)}
      </ScrollView>}
      <View style={s.row}>{['overview', 'details'].map(value => <Button key={value} onPress={() => setTab(value)} style={tab === value ? a.selectedSegment : a.segment}>{value === 'overview' ? 'Overview' : 'Details'}</Button>)}</View>
      {tab === 'overview' ? <>
        <Text selectable style={[s.label, { color: phaseColor }]}>{current.phase}</Text><Title style={a.peerName}>{current.peer}</Title>
        <Label>{current.count} files · {bytes(current.total)}{current.roots?.length ? `\n${current.roots.join(', ')}` : ''}</Label><Label>{current.message}</Label>
        {incoming && <Card><Label>{current.wrap ? 'The transfer will be saved in a separate folder.' : 'Files will be saved directly in your chosen folder.'}</Label><Label>{current.verify ? 'SHA-256 verification is enabled.' : 'SHA-256 verification is off.'}</Label></Card>}
        {!incoming && <><Progress value={percent} /><View style={s.between}><Label>{bytes(current.done)} / {bytes(current.total)}</Label><Label>{percent.toFixed(1)}%</Label></View>
          <Card style={a.metrics}>{[['Speed', `${bytes(current.speed)}/s`], ['Time remaining', duration(current.eta)], ['Elapsed', duration(current.elapsed)]].map(([label, value]) =>
            <View key={label} style={a.metric}><Label style={a.small}>{label}</Label><Text selectable style={a.metricValue}>{value}</Text></View>)}</Card>
          {!!current.current && <Label>{current.current}</Label>}{!!current.destination && <Label>Save to: {current.destination}</Label>}
        </>}
      </> : <>
        <View style={a.detailNav}>{[['fields', 'All diagnostics'], ['log', 'Activity log']].map(([value, label]) => <Button key={value} kind="ghost" onPress={() => setDetailsTab(value)} style={detailsTab === value && a.selectedSegment}>{label}</Button>)}</View>
        <Button kind="ghost" onPress={() => run(() => EtherDrop.copyDiagnostics(Object.entries(fields).map(([key, value]) => `${key}: ${value}`).join('\n') + '\n\n' + current.log.join('\n')))}>Copy diagnostics</Button>
        <Card>{detailsTab === 'fields' ? Object.entries(fields).map(([key, value]) => <View key={key} style={a.field}><Label style={a.small}>{key}</Label><Text selectable style={s.text}>{String(value)}</Text></View>) :
          <Text selectable style={a.log}>{current.log.join('\n\n') || 'No activity yet.'}</Text>}</Card>
      </>}
      <View style={a.bottomActions}>
        {incoming ? <><Button disabled={picking} kind="primary" onPress={accept}>Choose destination & accept</Button><Button kind="danger" disabled={picking} onPress={() => run(() => EtherDrop.decline(current.id))}>Decline</Button></> :
          !current.finished && <Button kind="danger" onPress={() => run(() => EtherDrop.cancel(current.id))}>Cancel transfer</Button>}
        {transfers.length > 1 && busy && <Button kind="danger" onPress={() => run(() => EtherDrop.cancelAll())}>Cancel all</Button>}
        {!busy && <Button kind="primary" onPress={() => run(async () => { await EtherDrop.dismiss(); setActiveId(''); setPage('main'); })}>Done</Button>}
      </View>
    </>;
  }

  return <SafeAreaProvider><SafeAreaView style={s.screen}><StatusBar barStyle="dark-content" backgroundColor={colors.bg} />
    <Animated.View style={[a.container, { opacity }]}><ScrollView contentInsetAdjustmentBehavior="automatic" contentContainerStyle={s.scroll}>
      {Platform.OS === 'web' && <Label style={a.small}>UI preview · file transfers run in the Android app</Label>}
      {page === 'home' && <><Text accessibilityRole="header" style={s.hero}>EtherDrop</Text><Label>Send and receive files nearby.</Label>
        <View style={a.connectionIntro}><Label>Connection</Label><View style={s.badgeSegment}><WifiBadge /></View><Label>Connect every device to the same Wi-Fi network.</Label></View>
        <Text accessibilityRole="header" style={a.sectionTitle}>Start a transfer</Text>
        {[['sender', 'Send files', 'Choose files and folders to share.', 'upload'], ['receiver', 'Receive files', 'Review incoming files and choose where to save them.', 'download']].map(([value, title, description, icon]) =>
          <Pressable key={value} accessibilityRole="button" onPress={() => start(value)} style={({ pressed }) => [a.roleCard, pressed && s.pressed]}>
            <Icon name={icon} size={36} /><View style={a.roleText}><Text style={a.roleTitle}>{title}</Text><Label>{description}</Label></View>
          </Pressable>)}<View style={a.homeFooter}><Button kind="ghost" onPress={() => setPage('help')}>How it works</Button></View>
      </>}
      {page === 'main' && <>{header(role === 'sender' ? 'Send files' : 'Receive files')}
        <View style={s.between}><Label style={a.summary}>{summary}</Label><Button kind="ghost" onPress={() => setPage('connection')}>Connection</Button></View>
        {role === 'sender' && state.network && !state.error ? <><Card style={a.fileCard}>
          <View style={s.between}><Text style={s.cardTitle}>{selected.length} {selected.length === 1 ? 'item' : 'items'} selected</Text><Button kind="ghost" onPress={() => { setSelected([]); setSelectedItem(''); }}>Clear</Button></View>
          <View style={a.fileButtons}><Button icon="file" style={a.fileButton} disabled={picking} onPress={() => add('files')}>Add files…</Button><Button icon="folder" style={a.fileButton} disabled={picking} onPress={() => add('folder')}>Add folder…</Button></View>
          <View style={a.fileList}>{selected.map(item => <Pressable key={item.uri} accessibilityRole="button" onPress={() => setSelectedItem(item.uri)} style={[a.fileRow, selectedItem === item.uri && a.selectedFile]}>
            <Icon name={item.folder ? 'folder' : 'file'} size={22} /><Text style={[s.text, a.fileName]}>{item.name}{item.folder ? ' /' : ''}</Text>{!item.folder && item.size >= 0 && <Label style={a.small}>{bytes(item.size)}</Label>}
          </Pressable>)}</View><Label style={a.small}>{selected.find(item => item.uri === selectedItem)?.name || (!selected.length ? 'Add files or folders to get started.' : '')}</Label>
        </Card><View style={a.senderActions}><Button kind="ghost" icon="settings" onPress={() => setPage('options')}>Transfer options</Button>
          <Button kind="primary" disabled={!connected || !selected.length || picking} style={a.sendButton} onPress={() => run(() => EtherDrop.send(selected.map(item => item.uri), verify, wrap))}>Send</Button></View></> : connectionState()}
      </>}
      {page === 'options' && <>{header('Transfer options')}<Card><Toggle title="Wrap transfer in a folder" description="Create a separate folder in the chosen destination. Turn off to save items directly." value={wrap} onChange={setWrap} />
        <Toggle title="Verify files with SHA-256" description="Checks every file during transfer. Leave off for maximum speed." value={verify} onChange={setVerify} /></Card></>}
      {page === 'connection' && <>{header('Connection')}<Card><Text selectable style={[s.text, { color: connected ? colors.success : state.error ? colors.danger : colors.muted }]}>{summary}</Text>
        <Label>Adapter: {state.network?.name || 'Not connected'}</Label><Label>Local address: {state.network?.address || '—'}</Label><Label>{connected ? `${connected} nearby ${connected === 1 ? 'device' : 'devices'}` : 'Waiting for another device.'}</Label>
      </Card>{connected > 0 && <Card><View style={s.between}><Label>Device</Label><Label>Address</Label></View>{state.peers.map(peer => <View style={s.between} key={peer.id}><Text selectable style={[s.text, a.fileName]}>{peer.name}</Text><Label>{peer.ip}</Label></View>)}</Card>}
        <Button icon="refresh" disabled={busy} onPress={() => run(() => EtherDrop.start(role))}>Rescan</Button><Label>{state.error || 'Keep every device on the same Wi-Fi network or hotspot. On Windows, select Wi-Fi mode. Guest networks may block nearby devices.'}</Label>
      </>}
      {page === 'help' && <>{header('How it works')}<Label>{'1. Join the same Wi-Fi network or hotspot on every device.\n\n2. Choose Send on one device and Receive on the others. On Windows, choose Wi-Fi mode.\n\n3. Add files or folders, then send. Every receiver chooses a destination and approves independently.\n\nOpen Connection for nearby devices and network details. During a transfer, Details contains diagnostics and the activity log.\n\nTransfers continue when you switch apps or lock the screen. Keep EtherDrop running; closing it cancels active transfers.'}</Label></>}
      {page === 'transfer' && transferPage()}
    </ScrollView></Animated.View>
    <Modal visible={!!conflict} transparent animationType="fade" onRequestClose={() => {}}><View style={a.modalBackdrop}><Card style={a.modalCard}><Title style={a.conflictTitle}>File already exists</Title><Label>{conflict}</Label><Label>Choose how to save this incoming file.</Label>
      {[['keep', 'Keep both files'], ['replace', 'Replace existing file'], ['skip', 'Skip this file']].map(([choice, label]) => <Button key={choice} kind={choice === 'keep' ? 'primary' : 'default'} onPress={() => run(async () => { await EtherDrop.resolveConflict(choice); setConflict(null); })}>{label}</Button>)}
    </Card></View></Modal>
  </SafeAreaView></SafeAreaProvider>;
}

const a = StyleSheet.create({
  container: { flex: 1 }, connectionIntro: { gap: 14, marginTop: 12 }, sectionTitle: { color: colors.text, fontSize: 22, fontWeight: '700', marginTop: 10 },
  roleCard: { backgroundColor: colors.surface, borderRadius: 10, padding: 24, flexDirection: 'row', alignItems: 'center', gap: 20, minHeight: 120 }, roleText: { flex: 1, gap: 6 }, roleTitle: { color: colors.text, fontSize: 17 },
  homeFooter: { flex: 1, minHeight: 40, alignItems: 'flex-end', justifyContent: 'flex-end' }, summary: { flex: 1 }, fileCard: { flex: 1, minHeight: 310 }, fileButtons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  fileList: { flex: 1, minHeight: 120, gap: 4 }, fileRow: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 12, paddingHorizontal: 6, borderRadius: 6 }, fileName: { flex: 1 }, selectedFile: { backgroundColor: colors.alternate },
  fileButton: { flex: 1, minWidth: 110, paddingHorizontal: 10 },
  senderActions: { flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', gap: 10 }, sendButton: { minWidth: 96, marginLeft: 'auto' },
  empty: { flex: 1, minHeight: 340, justifyContent: 'center', alignItems: 'center', gap: 18, paddingVertical: 36 }, centerTitle: { fontSize: 26, lineHeight: 34, textAlign: 'center' }, centerText: { textAlign: 'center' }, centerActions: { alignItems: 'center', gap: 8, paddingTop: 6 },
  peerTabs: { gap: 8 }, peerTab: { backgroundColor: colors.surface, padding: 14, borderRadius: 10, gap: 4 }, selectedSegment: { backgroundColor: colors.selected }, segment: { backgroundColor: colors.surface }, peerName: { fontSize: 36, lineHeight: 44 },
  metrics: { flexDirection: 'row', gap: 10, justifyContent: 'space-between' }, metric: { flex: 1, gap: 6 }, small: { fontSize: 13, lineHeight: 20 }, metricValue: { color: colors.text, fontSize: 17, fontVariant: ['tabular-nums'], flexShrink: 1 },
  detailNav: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 }, field: { gap: 3, paddingBottom: 12, borderBottomColor: colors.border, borderBottomWidth: StyleSheet.hairlineWidth }, log: { fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier', color: colors.text, fontSize: 13, lineHeight: 21 },
  bottomActions: { marginTop: 'auto', paddingTop: 14, gap: 10 }, modalBackdrop: { flex: 1, backgroundColor: 'rgba(34,37,43,0.35)', justifyContent: 'center', padding: 24 }, modalCard: { width: '100%', maxWidth: 460, alignSelf: 'center' }, conflictTitle: { fontSize: 24, lineHeight: 32 },
});
