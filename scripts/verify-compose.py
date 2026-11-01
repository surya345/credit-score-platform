#!/usr/bin/env python3
"""Build and verify an isolated disposable copy of the complete Docker stack.

Requires Python 3.10+ and Docker Compose v2. Uses random local ports and a unique
project/volumes. Never stops or deletes the normal credit-score-platform project.
"""
import argparse
import copy
import json
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.request import urlopen


def free_ports(count):
    sockets = []
    try:
        for _ in range(count):
            item = socket.socket()
            item.bind(('127.0.0.1', 0))
            sockets.append(item)
        return [item.getsockname()[1] for item in sockets]
    finally:
        for item in sockets:
            item.close()


def fixture(root, folder, project, ports):
    config = copy.deepcopy(json.loads((root / 'compose.yaml').read_text()))
    config['name'] = project
    front, identity, mailbox = ports
    for service in config['services'].values():
        service.pop('ports', None)
        service.pop('restart', None)
        build = service.get('build')
        if isinstance(build, str):
            service['build'] = str(root / build)
        elif build:
            build['context'] = str(root / build['context'])
        volumes = service.get('volumes', [])
        for index, mount in enumerate(volumes):
            if mount.startswith('./'):
                source, target = mount.split(':', 1)
                volumes[index] = str(root / source) + ':' + target
    services = config['services']
    services['frontend']['ports'] = [f'127.0.0.1:{front}:80']
    services['keycloak']['ports'] = [f'127.0.0.1:{identity}:8080']
    services['mailpit']['ports'] = [f'127.0.0.1:{mailbox}:8025']
    services['keycloak']['environment']['KC_HOSTNAME'] = f'http://localhost:{identity}'
    for name in ['user-service', 'data-service', 'scoring-service', 'report-service', 'gateway']:
        services[name]['environment']['OIDC_ISSUER'] = f'http://localhost:{identity}/realms/credit-platform'
    realm = (root / 'infra/keycloak/realm.json').read_text().replace('http://localhost:4200', f'http://localhost:{front}')
    (folder / 'realm.json').write_text(realm)
    services['keycloak']['volumes'] = [str(folder / 'realm.json') + ':/opt/keycloak/data/import/realm.json:ro']
    ui = json.loads((root / 'frontend/public/config.json').read_text())
    ui['url'] = f'http://localhost:{identity}'
    (folder / 'config.json').write_text(json.dumps(ui))
    services['frontend'].setdefault('volumes', []).append(str(folder / 'config.json') + ':/usr/share/nginx/html/config.json:ro')
    path = folder / 'compose.json'
    path.write_text(json.dumps(config, indent=2))
    return path


def read_json(url):
    with urlopen(url, timeout=5) as response:
        return json.load(response)


def wait_for(description, check, timeout=240):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, ValueError, KeyError) as error:
            last = error
        time.sleep(1)
    raise RuntimeError(f'Timed out waiting for {description}: {last}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keep', action='store_true', help='Keep only this isolated test stack for debugging')
    args = parser.parse_args()
    if not shutil.which('docker'):
        raise RuntimeError('Docker is unavailable. Compose verification was NOT run.')
    subprocess.run(['docker', 'info'], check=True, stdout=subprocess.DEVNULL, timeout=30)
    subprocess.run(['docker', 'compose', 'version'], check=True, timeout=30)
    root = Path(__file__).resolve().parents[1]
    project = 'credit-it-' + uuid.uuid4().hex[:12]
    folder = root / 'integration-tests/target/compose' / project
    folder.mkdir(parents=True)
    ports = free_ports(3)
    config = fixture(root, folder, project, ports)
    command = ['docker', 'compose', '--project-directory', str(root), '-p', project, '-f', str(config)]
    front, identity, mailbox = [f'http://localhost:{p}' for p in ports]
    started = False
    try:
        subprocess.run(command + ['config', '--quiet'], check=True, timeout=30)
        started = True
        subprocess.run(command + ['up', '--build', '-d'], check=True, timeout=1800)
        wait_for('Keycloak discovery', lambda: read_json(identity + '/realms/credit-platform/.well-known/openid-configuration')
                 .get('issuer') == identity + '/realms/credit-platform')
        wait_for('frontend runtime configuration', lambda: read_json(front + '/config.json').get('url') == identity)
        # Probe all business services before creating synthetic test data.
        for name in ['user-service', 'data-service', 'scoring-service', 'report-service', 'gateway']:
            wait_for(name + ' JVM startup', lambda name=name: subprocess.run(
                command + ['logs', '--no-color', name], capture_output=True, text=True, timeout=15).stdout.find('Started ') >= 0)
        subprocess.run([sys.executable, str(root / 'scripts/smoke-test.py'), '--base-url', front,
                        '--identity-url', identity, '--email'], check=True, timeout=240)
        keys = subprocess.check_output(command + ['exec', '-T', 'redis', 'redis-cli', '--scan', '--pattern', 'credit:score:v1:*'], text=True, timeout=20)
        if not keys.strip():
            raise AssertionError('No real Redis score cache entry was populated')
        stats = subprocess.check_output(command + ['exec', '-T', 'redis', 'redis-cli', 'INFO', 'stats'], text=True, timeout=20)
        hits = next(int(line.split(':')[1]) for line in stats.splitlines() if line.startswith('keyspace_hits:'))
        if hits < 1:
            raise AssertionError('No real Redis cache hit was recorded')
        wait_for('Mailpit receipt', lambda: read_json(mailbox + '/api/v1/messages').get('total', 0) > 0, timeout=30)
        messages = read_json(mailbox + '/api/v1/messages')['messages']
        matching = [m for m in messages if m['Subject'].startswith('Educational credit report ')]
        if len(matching) != 1:
            raise AssertionError('Expected one synthetic report email')
        message = read_json(mailbox + '/api/v1/message/' + matching[0]['ID'])
        if '763' not in message.get('Text', ''):
            raise AssertionError('The delivered email does not contain the expected score')
        print('PASS: Compose/MySQL, Nginx API proxy, Keycloak token flow, Kafka scoring/reporting, PDF, Redis cache and local SMTP', flush=True)
    finally:
        try:
            if started:
                try:
                    with (folder / 'compose.log').open('w') as output:
                        subprocess.run(command + ['logs', '--no-color'], stdout=output,
                                       stderr=subprocess.STDOUT, timeout=60)
                finally:
                    if not args.keep:
                        subprocess.run(command + ['down', '-v', '--remove-orphans'], check=True, timeout=120)
        finally:
            print('Verification artifacts:', folder, flush=True)
            if args.keep:
                print('Retained disposable project:', project, flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, AssertionError, subprocess.SubprocessError) as error:
        print('FAIL:', error, file=sys.stderr)
        sys.exit(1)
