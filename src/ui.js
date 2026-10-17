import { Image, Pressable, StyleSheet, Text, View } from 'react-native';

export const colors = {
  bg: '#F1F5FA', surface: '#FFFFFF', alternate: '#E6EDF7', panel: '#F7F9FD',
  border: '#D8E1EE', text: '#22252B', muted: '#686C76', accent: '#266CD3',
  success: '#237D52', warning: '#986416', danger: '#BC3E49', selected: '#B0C9EE',
};
const icons = {
  back: require('../assets/icons/arrow-left-ink-48.png'),
  upload: require('../assets/icons/upload-wifi-48.png'),
  download: require('../assets/icons/download-wifi-48.png'),
  wifi: require('../assets/icons/wifi-wifi-48.png'),
  wifiOff: require('../assets/icons/wifi-off-wifi-48.png'),
  file: require('../assets/icons/file-muted-48.png'),
  folder: require('../assets/icons/folder-muted-48.png'),
  settings: require('../assets/icons/settings-2-muted-48.png'),
  refresh: require('../assets/icons/refresh-cw-muted-48.png'),
};
export function Icon({ name, size = 24 }) {
  return <Image source={icons[name]} style={{ width: size, height: size }} resizeMode="contain" />;
}
export function WifiBadge() {
  return <View style={styles.row}><Icon name="wifi" size={20} /><Text style={{ color: colors.accent, fontSize: 14 }}>Wi-Fi</Text></View>;
}
export function Button({ children, onPress, kind = 'default', icon, disabled, style }) {
  return <Pressable accessibilityRole="button" accessibilityState={{ disabled: !!disabled }} disabled={disabled}
    onPress={onPress} style={({ pressed }) => [styles.button, styles[kind], disabled && styles.disabled, pressed && styles.pressed, style]}>
    {icon && <Icon name={icon} size={20} />}
    <Text style={[styles.buttonText, kind === 'primary' && styles.primaryText, kind === 'ghost' && styles.muted,
      kind === 'danger' && styles.dangerText, disabled && styles.muted]}>{children}</Text>
  </Pressable>;
}
export function Card({ children, style }) { return <View style={[styles.card, style]}>{children}</View>; }
export function Label({ children, style }) { return <Text selectable style={[styles.label, style]}>{children}</Text>; }
export function Title({ children, style }) { return <Text accessibilityRole="header" style={[styles.title, style]}>{children}</Text>; }
export function Toggle({ title, description, value, onChange }) {
  return <Pressable accessibilityRole="checkbox" accessibilityState={{ checked: value }} onPress={() => onChange(!value)} style={{ gap: 12, paddingVertical: 4 }}>
    <View style={styles.row}><View style={[styles.checkbox, value && { backgroundColor: colors.accent }]}>{value && <Text style={{ color: '#FFFFFF', fontWeight: '700' }}>✓</Text>}</View>
      <Text style={{ flexShrink: 1, fontSize: 16, color: colors.text }}>{title}</Text></View><Label style={{ fontSize: 14 }}>{description}</Label>
  </Pressable>;
}
export function Progress({ value }) {
  const percent = Math.max(0, Math.min(100, value || 0));
  return <View accessibilityRole="progressbar" accessibilityValue={{ min: 0, max: 100, now: Math.round(percent) }} style={styles.track}>
    <View style={{ height: '100%', backgroundColor: colors.accent, width: `${percent}%` }} />
  </View>;
}
export function bytes(value = 0) {
  if (value < 1024) return `${Math.round(value)} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let size = value / 1024, unit = 0;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
  return `${size.toFixed(size < 10 ? 2 : 1)} ${units[unit]}`;
}
export function duration(seconds) {
  if (seconds < 0 || !Number.isFinite(seconds)) return '—';
  const value = Math.floor(seconds);
  if (value >= 3600) return `${Math.floor(value / 3600)}h ${Math.floor(value % 3600 / 60)}m`;
  return value >= 60 ? `${Math.floor(value / 60)}m ${value % 60}s` : `${value}s`;
}
export const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.bg },
  scroll: { flexGrow: 1, paddingHorizontal: 24, paddingTop: 24, paddingBottom: 28, gap: 20 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  between: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  title: { color: colors.text, fontSize: 32, fontWeight: '700', lineHeight: 40, flexShrink: 1 },
  hero: { color: colors.text, fontSize: 40, fontWeight: '700', letterSpacing: -0.6 },
  label: { color: colors.muted, fontSize: 15, lineHeight: 23, flexShrink: 1 },
  text: { color: colors.text, fontSize: 15, lineHeight: 23 },
  muted: { color: colors.muted },
  button: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8, minHeight: 46,
    paddingVertical: 11, paddingHorizontal: 16, borderRadius: 10, backgroundColor: colors.alternate },
  buttonText: { color: colors.text, fontSize: 15, lineHeight: 22, textAlign: 'center', flexShrink: 1 },
  primary: { backgroundColor: colors.accent }, primaryText: { color: '#FFFFFF' },
  ghost: { backgroundColor: 'transparent', paddingHorizontal: 8 }, dangerText: { color: colors.danger },
  disabled: { backgroundColor: colors.alternate, opacity: 0.65 }, pressed: { opacity: 0.72 },
  card: { padding: 20, borderRadius: 18, backgroundColor: colors.surface, gap: 16 },
  cardTitle: { fontSize: 19, fontWeight: '700', color: colors.text },
  badgeSegment: { alignSelf: 'flex-start', backgroundColor: colors.selected, borderRadius: 10, paddingHorizontal: 24, paddingVertical: 12 },
  checkbox: { width: 21, height: 21, borderRadius: 4, backgroundColor: colors.alternate, alignItems: 'center', justifyContent: 'center' },
  track: { height: 6, borderRadius: 3, overflow: 'hidden', backgroundColor: colors.alternate },
});
