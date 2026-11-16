"""Validate provenance, unit-test results and lint delta without suppressing lint.

Run the Gradle checks first. Full-project lint retains its existing failures;
this separate check fails for any issue introduced beyond the recorded baseline.
"""
from pathlib import Path
from collections import Counter
import hashlib
import json
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root/'docs/porting/bitchord-sources.json').read_text())
for entry in manifest['files']:
    path = Path(manifest['source_root'])/entry['source']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == entry['sha256'], 'Donor changed: '+entry['source']

def signature(issue):
    location = issue.find('location')
    path = location.get('file','') if location is not None else ''
    path = re.sub('/+', '/', path.replace('\\','/'))
    path = 'src/'+path.partition('/src/')[2] if '/src/' in path else path
    return issue.get('id'),issue.get('severity'),issue.get('message'),path

baseline = ET.parse(root/'docs/porting/baseline-lint.xml').getroot()
current = ET.parse(root/'src/app/build/reports/lint-results-debug.xml').getroot()
new = Counter(signature(i) for i in current.findall('issue')) - Counter(signature(i) for i in baseline.findall('issue'))
reports = [ET.parse(p).getroot() for p in (root/'src/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
assert reports, 'No unit-test results found'
failures = sum(int(r.get('failures','0'))+int(r.get('errors','0')) for r in reports)
summary = {
    'donor_sources_verified':len(manifest['files']),
    'unit_tests':sum(int(r.get('tests','0')) for r in reports),
    'unit_test_failures':failures,
    'baseline_lint':dict(Counter(i.get('severity') for i in baseline.findall('issue'))),
    'current_lint':dict(Counter(i.get('severity') for i in current.findall('issue'))),
    'new_lint_issues':list(new.elements()),
}
(root/'docs/porting/verification.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
assert not failures, 'Unit tests failed'
assert not new, 'New lint issues introduced'
