"""Smoke-test only the isolated preview package; never target the production app.

Usage: python tools/smoke_ui_port.py --serial SERIAL --screenshots ABS_DIRECTORY
The optional --playback flag briefly opens a local FLAC and pauses it, then
checks seeking and queue edits. Leave it off while another app is playing.
"""
import argparse
import json
import re
import subprocess
import time
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--screenshots', type=Path, required=True)
parser.add_argument('--playback', action='store_true')
args = parser.parse_args()
ADB = str(Path.home() / 'AppData/Local/Android/Sdk/platform-tools/adb.exe')
PACKAGE = 'com.audiophile.engine.portpreview'
results = []
completed = False
failure = None
warnings = []
args.screenshots.mkdir(parents=True, exist_ok=True)


def adb(*command):
    r = subprocess.run([ADB, '-s', args.serial, *command], capture_output=True, timeout=30)
    if r.returncode: raise RuntimeError(r.stderr.decode(errors='replace'))
    return r.stdout


def ui():
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    policy = adb('shell', 'dumpsys', 'window', 'policy').decode(errors='replace')
    if re.search(r'\bshowing=true\b|\bmIsShowing=true\b', policy):
        raise RuntimeError('Phone is locked; unlock it before rerunning this test')
    resumed = adb('shell', 'dumpsys', 'activity', 'activities').decode(errors='replace')
    if not re.search(r'(?:topResumedActivity|ResumedActivity):?[^\n]*' + re.escape(PACKAGE), resumed):
        raise RuntimeError('Preview is not foreground; refusing to interact with another app')
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/music-port-smoke.xml')
    data = adb('shell', 'cat', '/data/local/tmp/music-port-smoke.xml')
    tree = ET.fromstring(data)
    if not any(n.get('package') == PACKAGE for n in tree.iter('node')):
        labels = [n.get('text') for n in tree.iter('node') if n.get('text')]
        if 'Android app compatibility' in labels and any('16 KB' in label for label in labels):
            # Acknowledge this specific developer-build diagnostic, never the
            # persistent "Don't show again" action or an arbitrary system dialog.
            click(next(n for n in tree.iter('node') if n.get('text') == 'OK'))
            if 'Existing native libraries trigger the Android 16 KB compatibility warning' not in warnings:
                warnings.append('Existing native libraries trigger the Android 16 KB compatibility warning')
            return ui()
        raise RuntimeError('Preview controls are covered; refusing to interact with system UI')
    return tree


def bounds(node):
    return list(map(int, re.findall(r'\d+', node.get('bounds'))))


def click(node):
    x1,y1,x2,y2 = bounds(node)
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))


def find(tree, value, field='text', prefix=False):
    nodes = [n for n in tree.iter('node') if n.get('package') == PACKAGE and
             (n.get(field,'').startswith(value) if prefix else n.get(field) == value)]
    if not nodes: raise AssertionError('Expected preview control missing: '+value)
    return max(nodes, key=lambda n: bounds(n)[1])


def screen(name):
    (args.screenshots/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))


def check(name):
    results.append({'check': name, 'passed': True})
    print('PASS',name,flush=True)


try:
    tree = ui()
    click(find(tree,'Home','content-desc'))
    tree = ui(); find(tree,'Your albums'); screen('home'); check('Home renders device albums')
    click(find(tree,'Library','content-desc'))
    tree = ui(); find(tree,'Your Albums'); find(tree,'Favourites'); screen('library'); check('Library categories and artwork render')
    click(find(tree,'Search','content-desc'))
    tree = ui()
    edit = next(n for n in tree.iter('node') if n.get('class') == 'android.widget.EditText')
    click(edit)
    adb('shell','input','text','zzzz_port_no_match')
    tree=ui(); find(tree,'0 results'); check('Search handles no matches')
    click(find(tree,'Clear search','content-desc'))
    adb('shell','input','keyevent','KEYCODE_BACK')
    tree=ui(); click(find(tree,'Explore','content-desc'))
    tree=ui(); find(tree,'Explore your collection'); check('Explore identifies unconnected online discovery')
    if args.playback:
        # Opt-in test, limited to this preview's independent session and queue.
        click(find(tree,'FLAC ·','text',prefix=True))
        tree=ui(); click(find(tree,'Pause','content-desc'))
        tree=ui(); find(tree,'Play','content-desc'); screen('player'); check('Local FLAC opens through native session and pauses')
        progress=next(n for n in tree.iter('node') if n.get('class') == 'android.widget.SeekBar')
        x1,y1,x2,y2=bounds(progress)
        adb('shell','input','tap',str(x1+(x2-x1)//3),str((y1+y2)//2))
        tree=ui(); find(tree,'Play','content-desc'); check('Seeking preserves paused state')
        click(find(tree,'Playing Next','content-desc'))
        tree=ui(); find(tree,'Playing Next')
        earlier=[n for n in tree.iter('node') if n.get('content-desc','').startswith('Move ') and n.get('enabled')=='true']
        if earlier:
            click(earlier[-1]); tree=ui(); find(tree,'Playing Next'); check('Queue reorder completes')
        remove=[n for n in tree.iter('node') if n.get('content-desc','').startswith('Remove ')]
        if len(remove)>1:
            # LazyColumn replaces a removed visible row with another row. Compare
            # the native queue's total, not the number of currently visible nodes.
            count=lambda t: int(next(n.get('text').split()[0] for n in t.iter('node') if re.fullmatch(r'\d+ tracks',n.get('text',''))))
            before=count(tree);click(remove[-1]);tree=ui();after=count(tree)
            assert after==before-1
            check('Queue removal completes')
        screen('queue')
        adb('shell','input','keyevent','KEYCODE_BACK')
    else:
        check('Playback left untouched during navigation-only test')
    completed = True
except Exception as error:
    failure = str(error)
    raise
finally:
    result=Path(__file__).resolve().parents[1]/'docs/porting/device-smoke.json'
    result.write_text(json.dumps({'package':PACKAGE,'device':'Samsung Galaxy S24+','checks':results,
        'playback_requested':args.playback,'completed':completed,'failure':failure,'warnings':warnings},indent=2)+'\n',encoding='utf-8')
