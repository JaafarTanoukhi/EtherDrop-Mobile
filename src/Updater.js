import { useEffect, useRef, useState } from 'react';
import { Linking, Modal, ScrollView, StyleSheet, View } from 'react-native';
import EtherDrop from '../modules/etherdrop';
import app from '../app.json';
import releaseNotes from './release-notes.json';
import { latestRelease, releasesUrl } from './updates';
import { Button, Card, Label, Progress, Title, bytes, styles as s } from './ui';

const version = app.expo.version;

export default function Updater({ home }) {
  const [checking, setChecking] = useState(false);
  const [dialog, setDialog] = useState(null);
  const [progress, setProgress] = useState({ done: 0, total: 0, speed: 0 });
  const [installMessage, setInstallMessage] = useState('');
  const checkingRef = useRef(false);
  const nativeAvailable = typeof EtherDrop.downloadUpdate === 'function';
  const downloading = dialog?.type === 'download';

  async function check(manual = false) {
    if (checkingRef.current) return;
    checkingRef.current = true;
    setChecking(true);
    try {
      let release;
      try { release = await latestRelease(version); }
      catch (error) { if (manual) throw error; }
      if (release) setDialog({ type: 'offer', release });
      else if (manual) setDialog({ type: 'notes', title: 'You’re up to date' });
      else if (nativeAvailable) {
        const seen = await EtherDrop.getSeenVersion();
        if (seen !== version) setDialog({ type: 'notes', title: seen ? 'EtherDrop updated' : 'Welcome to EtherDrop' });
      }
    } catch (error) {
      if (manual) setDialog({ type: 'error', message: error.message });
    } finally { checkingRef.current = false; setChecking(false); }
  }

  useEffect(() => {
    const timer = setTimeout(() => check(), 500);
    return () => clearTimeout(timer);
  }, []);
  useEffect(() => {
    if (!nativeAvailable) return;
    const subscription = EtherDrop.addListener('onUpdateProgress', setProgress);
    return () => subscription.remove();
  }, [nativeAvailable]);

  async function close() {
    if (downloading) return;
    if (dialog?.type === 'notes' && nativeAvailable) {
      try { await EtherDrop.markVersionSeen(version); }
      catch (error) { setDialog({ type: 'error', message: error.message }); return; }
    }
    setDialog(null);
  }
  async function install() {
    if (!await EtherDrop.installUpdate()) setInstallMessage('Allow installations from EtherDrop, then return here and tap Install update.');
  }
  async function download() {
    const release = dialog.release;
    setInstallMessage('');
    setProgress({ done: 0, total: release.size, speed: 0 });
    setDialog({ type: 'download', release });
    try {
      await EtherDrop.downloadUpdate(JSON.stringify(release));
      setDialog({ type: 'ready', release });
      await install();
    } catch (error) { setDialog({ type: 'error', message: error.message }); }
  }
  async function retryInstall() {
    try { await install(); }
    catch (error) { setDialog({ type: 'error', message: error.message }); }
  }
  const percent = progress.total ? progress.done / progress.total * 100 : 0;

  return <>
    {home && <View style={u.controls}><Button kind="ghost" disabled={checking} onPress={() => check(true)}>{checking ? 'Checking…' : 'Check for updates'}</Button><Label style={u.small}>Version {version}</Label></View>}
    <Modal visible={home && !!dialog} transparent animationType="fade" onRequestClose={close}>
      <View style={u.backdrop}><Card style={u.card}><ScrollView contentContainerStyle={u.content}>
        {dialog?.type === 'offer' && <><Title style={u.title}>Update available</Title><Label>EtherDrop {dialog.release.version} · {bytes(dialog.release.size)}</Label><Label>{dialog.release.notes}</Label>
          <Label>Android will ask you to confirm installation after the download.</Label>
          {!nativeAvailable && <Label>Install the next development APK to test downloading and installing updates.</Label>}
          <Button kind="primary" disabled={!nativeAvailable} onPress={download}>Download & install</Button>
        </>}
        {downloading && <><Title style={u.title}>Downloading update</Title><Label>EtherDrop {dialog.release.version}</Label><Progress value={percent} />
          <View style={s.between}><Label>{bytes(progress.done)} / {bytes(progress.total)}</Label><Label>{percent.toFixed(1)}%</Label></View><Label>{bytes(progress.speed)}/s</Label><Label>Keep EtherDrop open until the download finishes.</Label>
        </>}
        {dialog?.type === 'ready' && <><Title style={u.title}>Update ready</Title><Label>{installMessage || 'Complete the update in Android’s installer. If you canceled, you can open it again below.'}</Label><Button kind="primary" onPress={retryInstall}>Install update</Button></>}
        {dialog?.type === 'notes' && <><Title style={u.title}>{dialog.title}</Title><Label>Version {version}</Label><Label>{releaseNotes.notes}</Label><Button kind="ghost" onPress={() => Linking.openURL(releasesUrl)}>Open releases</Button></>}
        {dialog?.type === 'error' && <><Title style={u.title}>Couldn’t update EtherDrop</Title><Label>{dialog.message}</Label></>}
        {!downloading && <Button kind="ghost" onPress={close}>{dialog?.type === 'offer' ? 'Later' : 'Close'}</Button>}
      </ScrollView></Card></View>
    </Modal>
  </>;
}

const u = StyleSheet.create({
  controls: { alignItems: 'flex-end' }, small: { fontSize: 12, lineHeight: 18 },
  backdrop: { flex: 1, backgroundColor: 'rgba(34,37,43,0.35)', justifyContent: 'center', padding: 24 },
  card: { width: '100%', maxWidth: 460, maxHeight: '85%', alignSelf: 'center' },
  content: { gap: 18 }, title: { fontSize: 26, lineHeight: 34 },
});
