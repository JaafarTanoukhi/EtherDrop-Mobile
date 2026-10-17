import assert from 'node:assert/strict';
import { afterEach, mock, test } from 'node:test';
import { isNewer, latestRelease, selectRelease, versionParts } from '../src/updates.js';

const asset = { name: 'EtherDrop.apk', browser_download_url: 'https://github.com/example/app/releases/download/v1.0.1/EtherDrop.apk', size: 1234, digest: 'sha256:abc' };
const release = { tag_name: 'v1.0.1', body: 'Fix startup.', assets: [asset] };
afterEach(() => mock.restoreAll());

test('compares numeric release versions and accepts the v prefix', () => {
  assert.equal(isNewer('v1.0.10', '1.0.9'), true);
  assert.equal(isNewer('v2.0.0', '1.9.99'), true);
  assert.equal(isNewer('v1.0.0', '1.0.0'), false);
  assert.equal(isNewer('v0.9.9', '1.0.0'), false);
  assert.throws(() => versionParts('latest'), /Release versions/);
});

test('selects only the Android APK and preserves update metadata', () => {
  const result = selectRelease({ ...release, assets: [{ ...asset, name: 'EtherDrop.exe' }, asset] }, '1.0.0');
  assert.deepEqual(result, { version: 'v1.0.1', notes: 'Fix startup.', url: asset.browser_download_url, size: 1234, digest: 'sha256:abc' });
});

test('does not offer the installed version or a downgrade', () => {
  assert.equal(selectRelease(release, '1.0.1'), null);
  assert.equal(selectRelease(release, '2.0.0'), null);
});

test('a newer release without its APK is not offered for installation', () => {
  assert.throws(() => selectRelease({ ...release, assets: [] }, '1.0.0'), /does not contain EtherDrop.apk/);
});

test('an empty GitHub releases page is treated as no update', async () => {
  mock.method(globalThis, 'fetch', async () => ({ status: 404 }));
  assert.equal(await latestRelease('1.0.0'), null);
});

test('GitHub failures are reported instead of claiming the app is current', async () => {
  mock.method(globalThis, 'fetch', async () => ({ status: 503, ok: false }));
  await assert.rejects(latestRelease('1.0.0'), /HTTP 503/);
});

test('checks release metadata without downloading the APK', async () => {
  const fetch = mock.method(globalThis, 'fetch', async () => ({ status: 200, ok: true, json: async () => release }));
  assert.equal((await latestRelease('1.0.0')).version, 'v1.0.1');
  assert.equal(fetch.mock.calls.length, 1);
  assert.match(fetch.mock.calls[0].arguments[0], /api.github.com\/repos\/JaafarTanoukhi\/EtherDrop-Mobile\/releases\/latest$/);
});
