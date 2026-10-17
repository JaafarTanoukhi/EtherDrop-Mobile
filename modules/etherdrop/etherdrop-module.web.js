// Browser-only fixtures for inspecting the real React Native screens at phone sizes.
// The Android bundle always uses the actual native transfer engine.
const listeners = new Map();
let state = { role: '', error: '', network: null, peers: [], transfers: [] };
const network = { address: '192.168.1.20', name: 'Wi-Fi', device: 'Android phone', broadcast: '192.168.1.255' };
const peer = { id: 'preview-pc', name: 'Windows PC', ip: '192.168.1.10', role: 'receiver' };
function emit() { for (const listener of listeners.get('onState') || []) listener({ state: JSON.stringify(state) }); }
function transfer(direction) {
  return { id: 'preview-transfer', peer: 'Windows PC', ip: peer.ip, direction,
    phase: direction === 'Receiving' ? 'Incoming transfer' : 'Transferring', message: direction === 'Receiving' ?
      'Review these files and choose where to save them.' : 'Sending files directly over Wi-Fi.',
    total: 536870912, done: direction === 'Sending' ? 187904819 : 0, count: 24, current: 'Photos/holiday.jpg',
    verify: false, wrap: true, finished: false, awaitingApproval: direction === 'Receiving', elapsed: 12, speed: 15728640, eta: 22,
    destination: direction === 'Sending' ? 'Downloads/EtherDrop' : '', roots: ['Photos'], log: ['12:00:00  Transfer started.'] };
}
export default {
  addListener(name, listener) {
    if (!listeners.has(name)) listeners.set(name, new Set());
    listeners.get(name).add(listener);
    return { remove: () => listeners.get(name).delete(listener) };
  },
  async getState() { return JSON.stringify(state); },
  async start(role) {
    state = { role, network, error: '', peers: [{ ...peer, role: role === 'sender' ? 'receiver' : 'sender' }], transfers: [] };
    emit();
    if (role === 'receiver') { state.transfers = [transfer('Receiving')]; emit(); }
  },
  async stop() { state = { role: '', network: null, error: '', peers: [], transfers: [] }; emit(); },
  async pick(kind) { return kind === 'destination' ? [{ uri: 'preview-destination' }] :
    [{ uri: kind === 'folder' ? 'preview-folder' : 'preview-file', name: kind === 'folder' ? 'Photos' : 'Document.pdf', folder: kind === 'folder', size: 2457600 }]; },
  async send() { state.transfers = [transfer('Sending')]; emit(); },
  async accept() { state.transfers[0] = { ...transfer('Sending'), direction: 'Receiving' }; emit(); },
  async decline() { state.transfers[0] = { ...state.transfers[0], phase: 'Declined', finished: true }; emit(); },
  async cancel() { state.transfers = state.transfers.map(t => ({ ...t, phase: 'Cancelled', message: 'New transfer output removed.', finished: true })); emit(); },
  async cancelAll() { await this.cancel(); },
  async dismiss() { state.transfers = []; emit(); },
  async resolveConflict() {},
  async copyDiagnostics(text) { await navigator.clipboard.writeText(text); },
};
