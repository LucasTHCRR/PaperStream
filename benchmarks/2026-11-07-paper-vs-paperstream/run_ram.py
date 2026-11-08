# RAM comparison Paper vs PaperStream on a Pterodactyl server. Saves the server state and restores it afterwards.
import json, time, subprocess, urllib.request, urllib.parse, os, sys, re
K = os.environ['PTERO_KEY']
B = os.environ['PTERO_SERVER']  # https://<panel>/api/client/servers/<id>
CB = os.path.dirname(os.path.abspath(__file__))
SP = CB + '/tmp'
os.makedirs(SP, exist_ok=True)
JARS = {'paper': CB + '/jars/paper.jar', 'ps-medium': CB + '/jars/paperstream.jar', 'ps-beta': CB + '/jars/paperstream.jar'}
PROFILE = {'ps-medium': 'medium', 'ps-beta': 'performance-beta'}
ROUNDS = int(sys.argv[1]) if len(sys.argv) > 1 else 2
OUT = open(CB + '/ram.out', 'a')
RES = open(CB + '/ram-results.jsonl', 'a')

def log(*a):
    s = time.strftime('%H:%M:%S ') + ' '.join(str(x) for x in a); print(s, flush=True); OUT.write(s + '\n'); OUT.flush()

def req(p, data=None, method=None, raw=False, ctype='application/json'):
    r = urllib.request.Request(B + p, data=data, method=method, headers={'Authorization': 'Bearer ' + K, 'Accept': 'application/json', 'Content-Type': ctype})
    d = urllib.request.urlopen(r, timeout=120).read().decode()
    return d if raw or not d else json.loads(d)

def read(path): return req('/files/contents?file=' + urllib.parse.quote(path), raw=True)
def write(path, text): req('/files/write?file=' + urllib.parse.quote(path), text.encode(), 'POST', ctype='text/plain')
def rename(a, b): req('/files/rename', json.dumps({'root': '/', 'files': [{'from': a, 'to': b}]}).encode(), 'PUT')
def delete(files): req('/files/delete', json.dumps({'root': '/', 'files': files}).encode(), 'POST')
def power(s): req('/power', json.dumps({'signal': s}).encode(), 'POST')
def cmd(c): req('/command', json.dumps({'command': c}).encode(), 'POST')
def res(): return req('/resources')['attributes']
def state(): return res()['current_state']
def entries(d='/'): return [f['attributes'] for f in req('/files/list?directory=' + urllib.parse.quote(d))['data']]
def listing(d='/'): return [e['name'] for e in entries(d)]

def dirsize(d):
    total = 0
    for e in entries(d):
        total += e['size'] if e['is_file'] else dirsize(d.rstrip('/') + '/' + e['name'])
    return total

def upload(local, directory, name):
    tmp = SP + '/' + name
    subprocess.run(['cp', local, tmp], check=True)
    u = req('/files/upload')['attributes']['url']
    subprocess.run(['curl', '-s', '-o', '/dev/null', '-F', 'files=@' + tmp, u + '&directory=' + urllib.parse.quote(directory, safe='')], check=True)
    os.remove(tmp)

def logtext():
    try: return read('/logs/latest.log')
    except Exception: return ''

def wait_state(s, t=600):
    end = time.time() + t
    while time.time() < end:
        if state() == s: return True
        time.sleep(3)
    return False

def wait_log(needle, t):
    end = time.time() + t
    while time.time() < end:
        L = logtext()
        if needle in L: return L
        time.sleep(5)
    raise RuntimeError('timeout waiting for ' + needle)

def start():
    if 'latest.log' in listing('/logs'): delete(['logs/latest.log'])
    power('start'); L = wait_log('Done (', 900)
    m = re.search(r'Done \(([\d.]+)s\)', L)
    return L, float(m.group(1)) if m else None

def stop():
    power('stop'); wait_state('offline', 600)

def last(L, key):
    l = [x[x.index(key):] for x in L.splitlines() if key + ' ' in x][-1]
    return {k: float(v) for k, v in re.findall(r'(\w+)=([\d.]+)', l)}

# save state
if state() != 'offline':
    cmd('say RAM test starting, server restarts now'); time.sleep(5); stop()
saved = {p: read(p) for p in ['/server.properties', '/paperstream.yml'] if p.lstrip('/') in listing()}
rename('server.jar', 'server.jar.userbak')
plugins_before = listing('/plugins')
for p in [x for x in plugins_before if x.endswith('.jar')]:
    rename('plugins/' + p, 'plugins/' + p + '.off')
upload(CB + '/jars/ChunkBench.jar', '/plugins', 'ChunkBench.jar')
base_props = [l for l in saved['/server.properties'].splitlines() if not l.startswith(('level-name=', 'level-seed=', 'view-distance=', 'simulation-distance=', 'online-mode=', 'max-players='))]
base_yml = saved.get('/paperstream.yml', '')
log('saved state, plugins disabled:', [x for x in plugins_before if x.endswith('.jar')])

current_jar = None

def mem(tag, r):
    cmd('chunkbench mem ' + tag); L = wait_log('MEM tag=' + tag, 120)
    d = last(L, 'MEM tag=' + tag)
    d['rssMB'] = round(res()['resources']['memory_bytes'] / 1e6)
    r[tag] = d

try:
    for rnd in range(1, ROUNDS + 1):
        for name in ['paper', 'ps-medium', 'ps-beta']:
            world = 'cmp_' + name
            write('/server.properties', '\n'.join(base_props + ['level-name=' + world, 'level-seed=12345', 'view-distance=10', 'simulation-distance=8', 'online-mode=false']) + '\n')
            if world in listing(): delete([world])
            if JARS[name] != current_jar:
                if 'server.jar' in listing(): delete(['server.jar'])
                upload(JARS[name], '/', 'server.jar'); current_jar = JARS[name]
            if name in PROFILE:
                y = re.sub(r'(?m)^profile: .*', 'profile: ' + PROFILE[name], base_yml)
                write('/paperstream.yml', re.sub(r'(?m)^profile-applied: .*', 'profile-applied: none', y))
            elif 'paperstream.yml' in listing():
                delete(['paperstream.yml'])
            r = {'round': rnd, 'variant': name}
            start(); time.sleep(30)
            mem('idle', r)
            cmd('chunkbench 15 0 0 64'); wait_log('BENCH ', 1800); time.sleep(10)
            mem('pregen', r)
            cmd('chunkbench sweep 1000 1000 150 8 4'); wait_log('SWEEP ', 1800)
            mem('sweep', r)
            time.sleep(90)
            mem('unloaded', r)
            stop()
            log(json.dumps(r)); RES.write(json.dumps(r) + '\n'); RES.flush()
            delete([world])
finally:
    try:
        if state() != 'offline': stop()
    except Exception: pass
    for p in listing():
        if p.startswith('cmp_'): delete([p])
    delete(['plugins/ChunkBench.jar'])
    for p in listing('/plugins'):
        if p.endswith('.jar.off'): rename('plugins/' + p, 'plugins/' + p[:-4])
    if 'server.jar' in listing(): delete(['server.jar'])
    rename('server.jar.userbak', 'server.jar')
    for p, t in saved.items(): write(p, t)
    if 'latest.log' in listing('/logs'): delete(['logs/latest.log'])
    power('start')
    log('restored, server starting with original state')
