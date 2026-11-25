import csv, sys, re, glob, os
d = sys.argv[1]
rd = lambda f: list(csv.DictReader(open(os.path.join(d, f), encoding='utf-8', newline='')))
v = [r for f in sorted(glob.glob(d + '/vendors_*.csv')) for r in rd(os.path.basename(f))]
seen = {}; direct = set(); errs = []
for f in sorted(glob.glob(d + '/vendors_*.csv')):
    for r in rd(os.path.basename(f)):
        k = r['name'].strip().lower()
        if k in seen: errs.append('dup ' + r['name'])
        p = r['parent_vendor']
        if p and p.lower() not in direct: errs.append(f'parent not earlier/direct: {r["name"]}')
        seen[k] = 1
        if not p: direct.add(k)
        if len(r['name']) > 200 or len(r['service_description']) > 500: errs.append('len')
        if not re.fullmatch(r'[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+', r['email_domain']): errs.append('domain')
s = rd('systems_01.csv'); codes = {r['code'] for r in s}
for r in s:
    vs = r['vendors'].split(';')
    if any(x.lower() not in direct for x in vs): errs.append('system vendor not direct ' + r['code'])
b = rd('services_01.csv')
for r in b:
    e, sp = r['essential_systems'].split(';'), r['supporting_systems'].split(';')
    if set(e) & set(sp) or not set(e + sp) <= codes: errs.append('svc ' + r['code'])
dep = rd('vendor_dependencies.csv')
if len({(x['vendor'], x['depends_on_vendor']) for x in dep}) != len(dep): errs.append('dup dep')
print('vendors', len(v), 'direct', len(direct), 'systems', len(s), 'services', len(b), 'deps', len(dep))
print('ERRORS:', errs[:10] or 'none')
