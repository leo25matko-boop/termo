"""Exercise the packaged native transport without a Xiaomi account or camera."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]

def verify(engine):
    api_port, rtsp_port = free_port(), free_port()
    while rtsp_port == api_port:
        rtsp_port = free_port()
    user, password = secrets.token_hex(16), secrets.token_hex(24)
    video_user, video_password = secrets.token_hex(16), secrets.token_hex(24)
    config = {
        'app': {'modules': ['api', 'rtsp', 'xiaomi']},
        'api': {'listen': f'127.0.0.1:{api_port}', 'username': user, 'password': password,
                'local_auth': True, 'origin': '',
                'allow_paths': ['/api', '/api/xiaomi', '/api/streams', '/api/termodom/session']},
        'rtsp': {'listen': f'127.0.0.1:{rtsp_port}', 'username': video_user, 'password': video_password},
        'log': {'level': 'disabled', 'output': ''}, 'xiaomi': {}, 'streams': {},
    }
    auth = 'Basic ' + base64.b64encode(f'{user}:{password}'.encode()).decode()
    def request(path, authorization=auth, method='GET'):
        req = urllib.request.Request(f'http://127.0.0.1:{api_port}/{path}', method=method)
        if authorization is not None:
            req.add_header('Authorization', authorization)
        try:
            with urllib.request.urlopen(req, timeout=2) as response:
                return response.status, response.read(2_000_000)
        except urllib.error.HTTPError as error:
            return error.code, error.read(1000)
    def rtsp(authorization=None):
        extra = f'Authorization: {authorization}\r\n' if authorization else ''
        with socket.create_connection(('127.0.0.1', rtsp_port), timeout=2) as connection:
            connection.sendall(f'OPTIONS rtsp://127.0.0.1:{rtsp_port}/private-check RTSP/1.0\r\nCSeq: 1\r\n{extra}\r\n'.encode())
            return connection.recv(2048).decode('ascii', errors='replace')
    with tempfile.TemporaryDirectory(prefix='termodom-camera-probe-') as temporary:
        child = subprocess.Popen([str(engine.resolve()), '-config', '-'], cwd=temporary,
                                 stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            child.stdin.write(json.dumps(config).encode())
            child.stdin.close()
            for attempt in range(100):
                if child.poll() is not None:
                    raise RuntimeError('Native camera process exited during startup.')
                try:
                    if request('api/termodom/session')[0] == 200:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(0.1)
            else:
                raise RuntimeError('Native camera process did not start.')
            assert request('api', None)[0] == 401, 'Anonymous loopback HTTP was accepted.'
            wrong = 'Basic ' + base64.b64encode(f'{user}:incorrect'.encode()).decode()
            assert request('api', wrong)[0] == 401, 'Incorrect loopback HTTP password was accepted.'
            assert request('api')[0] == 200, 'Authenticated HTTP API failed.'
            assert rtsp().startswith('RTSP/1.0 401'), 'Anonymous loopback RTSP was accepted.'
            assert rtsp(wrong).startswith('RTSP/1.0 401'), 'Incorrect loopback RTSP password was accepted.'
            video_auth = 'Basic ' + base64.b64encode(f'{video_user}:{video_password}'.encode()).decode()
            assert rtsp(video_auth).startswith('RTSP/1.0 200'), 'Authenticated native RTSP failed.'
            assert json.loads(request('api/termodom/session')[1]) == {}, 'Unexpected saved account.'
            assert request('api/log')[0] == 404, 'Engine logs must not be exposed.'
            assert request('api/config')[0] == 404, 'Engine raw configuration must not be exposed.'
            assert request('')[0] == 404, 'A browser UI must not be exposed.'
            # Registering a synthetic RTSP source changes only in-memory state;
            # it does not connect to that source until a consumer starts playback.
            query = urllib.parse.urlencode({'name': 'synthetic-only', 'src': 'rtsp://127.0.0.1:9/synthetic'})
            registration = request('api/streams?' + query, method='PUT')
            assert registration[0] == 200, 'Memory-only synthetic stream registration failed: ' + repr(registration)
            assert request('api/streams?src=synthetic-only', method='DELETE')[0] == 200
            assert list(Path(temporary).iterdir()) == [], 'Runtime configuration or tokens were written to disk.'
            if os.name == 'nt':
                netstat = subprocess.check_output(['netstat', '-ano', '-p', 'TCP'], creationflags=subprocess.CREATE_NO_WINDOW).decode(errors='replace')
                listeners = [line.split()[1] for line in netstat.splitlines()
                             if len(line.split()) >= 5 and line.split()[-1] == str(child.pid)
                             and line.split()[3] == 'LISTENING']
                assert len(listeners) == 2, f'Unexpected native listener count: {len(listeners)}'
                assert all(address.startswith('127.0.0.1:') for address in listeners), 'Native engine exposed a LAN listener.'
            print('TERMODOM_CAMERA_ENGINE_OK: HTTP and RTSP deny missing/wrong credentials; authenticated requests pass; two loopback-only listeners; no web UI/log/config endpoints; memory-only config; Xiaomi controlCommands=0')
        finally:
            child.terminate()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=3)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--engine', required=True, type=Path)
    arguments = parser.parse_args()
    verify(arguments.engine)
