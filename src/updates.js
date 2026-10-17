export const repository = 'JaafarTanoukhi/EtherDrop-Mobile';
export const releasesUrl = `https://github.com/${repository}/releases/latest`;

export function versionParts(value) {
  if (!/^v?\d+\.\d+\.\d+$/.test(value)) throw new Error('Release versions must use vMAJOR.MINOR.PATCH.');
  return value.replace(/^v/, '').split('.').map(Number);
}

export function isNewer(candidate, current) {
  const left = versionParts(candidate), right = versionParts(current);
  for (let index = 0; index < 3; index++) {
    if (left[index] !== right[index]) return left[index] > right[index];
  }
  return false;
}

export function selectRelease(release, current) {
  if (!isNewer(release.tag_name, current)) return null;
  const asset = release.assets.find(item => item.name === 'EtherDrop.apk');
  if (!asset) throw new Error('The latest release does not contain EtherDrop.apk yet.');
  return { version: release.tag_name, notes: release.body || 'No update notes were provided.',
    url: asset.browser_download_url, size: asset.size, digest: asset.digest || '' };
}

export async function latestRelease(current) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch(`https://api.github.com/repos/${repository}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json' }, signal: controller.signal,
    });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`GitHub update check failed (HTTP ${response.status}).`);
    return selectRelease(await response.json(), current);
  } finally { clearTimeout(timeout); }
}
