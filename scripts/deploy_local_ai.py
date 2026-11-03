"""Install the native debug APK and provision the pinned local model on an emulator.

No downloads, uninstall, app-data clearing, or replacement of existing model files.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import uuid
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKAGE = 'app.finance.android'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def publish_asset(command, text, temporary, target, checksum):
    # Android run-as SELinux disallows hard links on some images. Same-directory
    # mv -n publishes the verified file without replacing an existing target.
    command('shell', 'run-as', PACKAGE, 'mv', '-n', temporary, target, capture_output=True)
    if text('shell', 'run-as', PACKAGE, 'sha256sum', target).split()[0] != checksum:
        raise SystemExit('Existing/published model asset differs; refusing overwrite')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=pathlib.Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', required=True, type=pathlib.Path)
    parser.add_argument('--model', type=pathlib.Path)
    parser.add_argument('--system', type=pathlib.Path)
    parser.add_argument('--install-only', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise SystemExit('An explicit emulator serial is required')
    base = [str(args.adb), '-s', args.serial]

    def command(*parts, **kwargs):
        return subprocess.run(base + list(parts), check=True, **kwargs)

    def text(*parts):
        return command(*parts, capture_output=True, text=True).stdout.strip()

    if text('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Only an emulator is authorized by this helper')
    with zipfile.ZipFile(args.apk) as apk:
        if 'lib/arm64-v8a/libfinance_inference.so' not in apk.namelist():
            raise SystemExit('APK lacks the native runtime: build with -PnativeAi=true')
    manifest = json.loads((ROOT / 'docs/models/day09-iteration1.json').read_text())
    memory = text('shell', 'cat', '/proc/meminfo')
    total = int(re.search(r'^MemTotal:\s+(\d+)', memory, re.MULTILINE)[1]) * 1024
    if not args.install_only:
        if total < 7 * 1024**3:
            raise SystemExit('Configure at least 8GB emulator RAM before provisioning; --install-only preserves manual mode')
        if not args.model or not args.system:
            raise SystemExit('--model and --system are required')
        if args.model.stat().st_size != manifest['artifact']['bytes'] or digest(args.model) != manifest['artifact']['sha256']:
            raise SystemExit('Model identity mismatch')
        if digest(args.system) != manifest['files']['system.txt']:
            raise SystemExit('System prompt identity mismatch')
    command('install', '-r', str(args.apk), capture_output=True)
    if args.install_only:
        print('Native APK installed; model not provisioned (--install-only).')
        return

    # Each version owns its directory. Upgrading never overwrites/deletes day-07 assets.
    folder = 'files/models/day09-iteration1-q4-k-m'
    expected = [('model.gguf', args.model, manifest['artifact']['sha256']),
                ('system.txt', args.system, manifest['files']['system.txt'])]
    command('shell', 'run-as', PACKAGE, 'mkdir', '-p', folder, capture_output=True)
    for name, source, checksum in expected:
        target = folder + '/' + name
        probe = subprocess.run(base + ['shell', 'run-as', PACKAGE, 'test', '-e', target], capture_output=True)
        if probe.returncode == 0:
            if text('shell', 'run-as', PACKAGE, 'sha256sum', target).split()[0] == checksum:
                continue
            raise SystemExit('Existing model asset differs; refusing overwrite')
        temporary = target + '.part-' + uuid.uuid4().hex
        try:
            # All remote paths are fixed app-private constants plus generated hex, never user text.
            with source.open('rb') as stream:
                command('shell', '-T', f"run-as {PACKAGE} sh -c 'cat > {temporary}'", stdin=stream, capture_output=True)
            if text('shell', 'run-as', PACKAGE, 'sha256sum', temporary).split()[0] != checksum:
                raise SystemExit('Transferred asset checksum mismatch')
            publish_asset(command, text, temporary, target, checksum)
        finally:
            command('shell', 'run-as', PACKAGE, 'rm', '-f', temporary, capture_output=True)
    print('Verified app-private model provisioned; AI connects automatically on entry.')


if __name__ == '__main__':
    main()
