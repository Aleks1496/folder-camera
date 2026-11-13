const repository = "Aleks1496/folder-camera";
const assetName = "folder-camera-receiver-windows-x64.zip";

type Release = {
  tag_name: string;
  draft: boolean;
  prerelease: boolean;
  assets: { name: string; browser_download_url: string; digest?: string }[];
};

function releaseOrder(tag: string): number | undefined {
  const preview = /^folder-camera-build-([1-9]\d*)$/.exec(tag);
  if (preview) {
    const number = Number(preview[1]);
    return Number.isSafeInteger(number) ? number : undefined;
  }
  const stable = /^folder-camera-v(\d+)\.(\d+)\.(\d+)$/.exec(tag);
  if (!stable) return;
  const parts = stable.slice(1).map(Number);
  if (parts.some((part) => part > 9999)) return;
  return parts.reduce((total, part) => total * 10_000 + part, 0);
}

export async function availableUpdate(current: string) {
  const order = releaseOrder(current);
  if (order === undefined) return null;
  const preview = current.startsWith("folder-camera-build-");
  const response = await fetch(
    `https://api.github.com/repos/${repository}/releases?per_page=100`,
    { signal: AbortSignal.timeout(15_000), headers: { Accept: "application/vnd.github+json" } },
  );
  if (!response.ok) throw new Error(`GitHub returned ${response.status}`);
  const releases: Release[] = await response.json();
  let best = order;
  let update: { tag: string; url: string; digest: string } | null = null;
  for (const release of releases) {
    if (release.draft || release.prerelease !== preview ||
        release.tag_name.startsWith("folder-camera-build-") !== preview) continue;
    const candidate = releaseOrder(release.tag_name);
    if (candidate === undefined || candidate <= best) continue;
    const url = `https://github.com/${repository}/releases/download/${release.tag_name}/${assetName}`;
    const asset = release.assets.find((item) => item.name === assetName);
    if (asset?.browser_download_url !== url || !/^sha256:[a-f0-9]{64}$/.test(asset.digest ?? "")) continue;
    best = candidate;
    update = { tag: release.tag_name, url, digest: asset.digest! };
  }
  return update;
}

export async function downloadUpdate(update: NonNullable<Awaited<ReturnType<typeof availableUpdate>>>) {
  const response = await fetch(update.url, { signal: AbortSignal.timeout(180_000) });
  if (!response.ok || !response.body) throw new Error("Could not download receiver update");
  const buffer = new Uint8Array(100 * 1024 * 1024);
  let length = 0;
  for await (const chunk of response.body) {
    if (length + chunk.length > buffer.length) throw new Error("Receiver update is too large");
    buffer.set(chunk, length);
    length += chunk.length;
  }
  const bytes = buffer.subarray(0, length);
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
  if (`sha256:${digest}` !== update.digest) throw new Error("Receiver update checksum mismatch");
  return bytes;
}
