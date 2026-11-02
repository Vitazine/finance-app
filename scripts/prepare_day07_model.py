"""Rebuild the owner's local research GGUF without modifying original artifacts.

Requires an isolated Python with mlx-lm/torch/transformers, and the pinned llama.cpp
checkout/quantizer. No downloads, remote code execution, corpus reads or uploads.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / 'docs/models/day07-iteration4.json'


def sha(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('base', 'adapter', 'template', 'python', 'llama-source', 'quantizer', 'output'):
        parser.add_argument('--' + flag, type=pathlib.Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text())
    expected = [(args.base / 'model.safetensors', manifest['base']['weights_sha256']),
                (args.adapter / 'adapters.safetensors', manifest['adapter']['sha256']),
                (args.template, manifest['files']['chat_template.jinja'])]
    for path, digest in expected:
        if sha(path) != digest:
            raise SystemExit('Input hash mismatch; no conversion performed')
    revision = subprocess.check_output(['git', '-C', str(args.llama_source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != manifest['runtime']['revision']:
        raise SystemExit('Runtime revision mismatch')
    if args.output.exists():
        raise SystemExit('Output must be a new directory; refusing to overwrite')
    for source in (args.base, args.adapter, args.template.parent, args.llama_source):
        if args.output.resolve().is_relative_to(source.resolve()):
            raise SystemExit('Output must be outside source artifacts')
    args.output.mkdir(parents=True)
    fused = args.output / 'fused'
    env = dict(os.environ, HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', TOKENIZERS_PARALLELISM='false')
    commands = [
        [str(args.python), '-m', 'mlx_lm.fuse', '--model', str(args.base), '--adapter-path', str(args.adapter), '--save-path', str(fused), '--dequantize'],
        [str(args.python), str(args.llama_source / 'convert_hf_to_gguf.py'), str(fused), '--outfile', str(args.output / 'model-f16.gguf'), '--outtype', 'f16', '--model-name', 'Finance-Day07-Iteration4'],
        [str(args.quantizer), str(args.output / 'model-f16.gguf'), str(args.output / 'model-Q4_K_M.gguf'), 'Q4_K_M', '4'],
    ]
    for index, command in enumerate(commands):
        with (args.output / f'stage-{index}.log').open('w') as log:
            subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        if index == 0:
            tokenizer_path = fused / 'tokenizer_config.json'
            tokenizer = json.loads(tokenizer_path.read_text())
            tokenizer['chat_template'] = args.template.read_text()
            tokenizer_path.write_text(json.dumps(tokenizer, ensure_ascii=False, indent=2) + '\n')
            (fused / 'chat_template.jinja').write_bytes(args.template.read_bytes())
    artifact = args.output / 'model-Q4_K_M.gguf'
    report = {'bytes': artifact.stat().st_size, 'sha256': sha(artifact),
              'originals_unchanged': all(sha(p) == h for p, h in expected),
              'runtime_revision': revision, 'network': 'OFFLINE'}
    (args.output / 'conversion.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
