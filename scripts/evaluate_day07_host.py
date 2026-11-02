"""Run synthetic day-07 smoke with pinned local llama-completion. Not a release gate.

Use the isolated inference Python (transformers required). All inputs are local;
no original corpus is read. Logs/prompts remain in the explicitly chosen output.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('model', 'tokenizer', 'template', 'system', 'binary', 'output'):
        parser.add_argument('--' + name, required=True, type=pathlib.Path)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Output exists; refusing to overwrite evidence')
    args.output.mkdir(parents=True)
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1')
    from transformers import AutoTokenizer
    tokenizer = AutoTokenizer.from_pretrained(str(args.tokenizer), local_files_only=True, trust_remote_code=False)
    tokenizer.chat_template = args.template.read_text()
    system = args.system.read_text().strip()
    corpus = json.loads((ROOT / 'docs/testing/day07-smoke.json').read_text())
    report = {'device': 'host CPU; NOT Android or physical qualification',
              'corpus': 'day07-smoke v1; training overlap not attested',
              'context': 2048, 'threads': 4, 'temperature': 0, 'max_output_tokens': 256,
              'system_sha256': hashlib.sha256(system.encode()).hexdigest(), 'cases': []}
    for index, case in enumerate(corpus['cases']):
        prompt = tokenizer.apply_chat_template([
            {'role': 'system', 'content': system},
            {'role': 'user', 'content': json.dumps({'text': case['prompt'], 'current_date': corpus['reference_date']}, ensure_ascii=False)}
        ], tokenize=False, add_generation_prompt=True)
        prompt_path = args.output / 'transient-prompt.txt'
        prompt_path.write_text(prompt)
        started = time.monotonic()
        row = {'id': case['id']}
        with (args.output / f'output-{index}.txt').open('w') as output, (args.output / f'log-{index}.txt').open('w') as log:
            try:
                proc = subprocess.run([str(args.binary), '-m', str(args.model), '-f', str(prompt_path),
                                       '-c', '2048', '-n', '256', '-t', '4', '-b', '256', '--temp', '0',
                                       '--no-warmup', '--no-display-prompt', '-no-cnv', '--simple-io'],
                                      stdout=output, stderr=log, timeout=300)
                row['exit'] = proc.returncode
            except subprocess.TimeoutExpired:
                row['timeout'] = True
        raw = (args.output / f'output-{index}.txt').read_text().strip()
        # Known completion executable terminal marker; never use this extraction in the app.
        if raw.endswith(' [end of text]'):
            raw = raw[:-len(' [end of text]')].rstrip()
        try:
            data = json.loads(raw)
            row['json'] = True
            row['expected_subset_match'] = isinstance(data, dict) and all(data.get(k) == v for k, v in case['expected_subset'].items())
            row['response'] = data
        except ValueError:
            row.update(json=False, expected_subset_match=False)
        row['seconds'] = round(time.monotonic() - started, 3)
        report['cases'].append(row)
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        prompt_path.unlink()
        print(case['id'], row['json'], row['expected_subset_match'], row['seconds'], flush=True)
    # A partial field subset score is deliberately not labelled exact/schema/98%-quality.
    print('Expected subset matches:', sum(x['expected_subset_match'] for x in report['cases']), '/', len(report['cases']))


if __name__ == '__main__':
    main()
