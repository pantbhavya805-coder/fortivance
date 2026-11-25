#!/usr/bin/env python3
"""Generate fictional banking third-party-risk load-test CSVs."""
import csv
import io
import random
from datetime import date, timedelta
from pathlib import Path
from collections import Counter

# Configuration
SEED = 42
DIRECT_COUNT = 1500
SUBCONTRACTOR_COUNT = 3500
SYSTEM_COUNT = 1200
SERVICE_COUNT = 400
HOTSPOT_COUNT = 20
CRITICAL_HOTSPOT_COUNT = 5
HOTSPOT_MIN = 0.25
HOTSPOT_MAX = 0.40
CRITICAL_HOTSPOT_SHARE = 0.85
SHARED_COUNT = 40
EXTRA_PAIRS = 300
SHARED_MIN = 3
SHARED_MAX = 15
SYSTEM_VENDOR_MIN, SYSTEM_VENDOR_MAX = 8, 14
ESSENTIAL_MIN, ESSENTIAL_MAX = 3, 8
SUPPORTING_MIN, SUPPORTING_MAX = 2, 6
MAX_ROWS = 2000
MAX_BYTES = 1_000_000
OUTPUT_DIR = Path(__file__).resolve().parent / 'data'
BASE_DATE = date(2026, 1, 1)

VENDOR_HEADER = 'name,parent_vendor,service_category,service_description,risk_tier,material,data_access,outsourcing_scope,scope_note,email_domain,contract_start,contract_end,last_assessed_on,next_review_due'.split(',')
SYSTEM_HEADER = 'code,name,description,criticality,owner_team,rto_hours,rpo_minutes,vendors,single_point_vendors'.split(',')
SERVICE_HEADER = 'code,name,description,customer_facing,criticality,impact_tolerance_hours,bcp_plan_ref,business_owner,essential_systems,supporting_systems'.split(',')
DEPENDENCY_HEADER = ['vendor', 'depends_on_vendor']
CATEGORIES = 'CLOUD_HOSTING PAYMENTS IDENTITY_VERIFICATION MESSAGING DATA_CENTRE FRAUD_ANALYTICS CORE_BANKING CARD_PROCESSING SECURITY_OPERATIONS CALL_CENTRE'.split()
TIERS = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']
TEAMS = ['Platform Engineering', 'Payments Operations', 'Digital Banking', 'Security Engineering', 'Core Systems', 'Customer Operations']
PREFIXES = ['Zorvexa', 'Quenlivo', 'Varnyxo', 'Trelvuni', 'Nexqora', 'Fynzelo', 'Orvexiq', 'Kelzuno']
SUFFIXES = ['Talvex', 'Qirnox', 'Zelvani', 'Myrqen', 'Drovixa', 'Pelnivo']


def weighted(values, weights):
    return random.choices(values, weights=weights, k=1)[0]


def csv_line(row):
    buffer = io.StringIO(newline='')
    csv.writer(buffer, lineterminator='\r\n', quoting=csv.QUOTE_MINIMAL).writerow(row)
    return buffer.getvalue().encode('utf-8')


def write_chunks(stem, header, groups, stats):
    """Each group starts a new chunk; limits include header bytes."""
    head = csv_line(header)
    index = 0
    for group in groups:
        payload = bytearray(head)
        count = 0
        def flush():
            nonlocal index, payload, count
            if not count:
                return
            index += 1
            path = OUTPUT_DIR / f'{stem}_{index:02d}.csv'
            path.write_bytes(payload)
            stats.append((path.name, count, path.stat().st_size))
            payload, count = bytearray(head), 0
        for row in group:
            encoded = csv_line(row)
            if len(head) + len(encoded) > MAX_BYTES:
                raise ValueError('Single row exceeds byte limit')
            if count >= MAX_ROWS or len(payload) + len(encoded) > MAX_BYTES:
                flush()
            payload.extend(encoded)
            count += 1
        flush()


def vendor_row(i, parent=''):
    name = f'{random.choice(PREFIXES)} {random.choice(SUFFIXES)} V{i:05d} Pvt Ltd'
    category = random.choice(CATEGORIES)
    tier = weighted(TIERS, [10, 30, 40, 20])
    dates = ['', '', '', '']
    if not parent:
        start = BASE_DATE - timedelta(days=random.randint(365, 1800))
        end = BASE_DATE + timedelta(days=random.randint(30, 1095))
        assessed = BASE_DATE - timedelta(days=random.randint(0, 180))
        review = assessed + timedelta(days=random.choice([90, 180, 365]))
        dates = [d.isoformat() for d in (start, end, assessed, review)]
    return [name, parent, category, category.replace('_', ' ').title() + ' services', tier,
            weighted(['yes', 'no'], [80, 20] if tier in TIERS[:2] else [30, 70]),
            random.choice(['FINANCIAL', 'CUSTOMER_PII', 'INTERNAL', 'NONE']),
            weighted(['IN_SCOPE', 'EXCLUDED', 'UNDER_REVIEW'], [85, 5, 10]), '',
            f'fictional-v{i:05d}.example', *dates]


def generate_systems(direct_names):
    hotspots = random.sample(direct_names, HOTSPOT_COUNT)
    critical_hotspots = hotspots[:CRITICAL_HOTSPOT_COUNT]
    ordinary = sorted(set(direct_names) - set(hotspots))
    levels = [weighted(TIERS, [15, 30, 35, 20]) for _ in range(SYSTEM_COUNT)]
    critical = [i for i, level in enumerate(levels) if level == 'CRITICAL']
    assignments = [set() for _ in levels]
    targets = [random.randint(SYSTEM_VENDOR_MIN, SYSTEM_VENDOR_MAX) for _ in levels]
    # Allocate exact hotspot quotas, balancing load across available systems.
    for vendor in hotspots:
        quota = random.randint(int(SYSTEM_COUNT * HOTSPOT_MIN + 0.999999), int(SYSTEM_COUNT * HOTSPOT_MAX))
        chosen = []
        if vendor in critical_hotspots:
            required = int(len(critical) * CRITICAL_HOTSPOT_SHARE + 0.999999)
            eligible = [i for i in critical if len(assignments[i]) < targets[i]]
            if required > quota or required > len(eligible):
                raise ValueError('Critical hotspot quota infeasible')
            chosen = random.sample(eligible, required)
        for i in chosen:
            assignments[i].add(vendor)
        eligible = [i for i in range(SYSTEM_COUNT) if vendor not in assignments[i] and len(assignments[i]) < targets[i]]
        if quota - len(chosen) > len(eligible):
            raise ValueError('Hotspot quota infeasible')
        for i in random.sample(eligible, quota - len(chosen)):
            assignments[i].add(vendor)
    rows = []
    for i, level in enumerate(levels):
        names = sorted(assignments[i]) + random.sample(ordinary, targets[i] - len(assignments[i]))
        random.shuffle(names)
        single = random.sample(names, random.randint(0, 2))
        rows.append([f'SYS-{i+1:04d}', f'Synthetic System {i+1:04d}', 'Fictional banking application', level,
                     random.choice(TEAMS), random.randint(1, 72), random.randint(5, 1440), ';'.join(names), ';'.join(single)])
    return rows, hotspots, critical_hotspots


def generate_services(codes):
    rows = []
    for i in range(1, SERVICE_COUNT + 1):
        level = weighted(TIERS, [15, 30, 35, 20])
        a = random.randint(ESSENTIAL_MIN, ESSENTIAL_MAX)
        b = random.randint(SUPPORTING_MIN, SUPPORTING_MAX)
        selected = random.sample(codes, a + b)
        rows.append([f'SVC-{i:04d}', f'Synthetic Business Service {i:04d}', 'Fictional banking business service',
                     weighted(['yes', 'no'], [70, 30]), level, random.randint(1, 4) if level == 'CRITICAL' else random.randint(4, 24),
                     f'BCP-{i:03d}', random.choice(TEAMS), ';'.join(selected[:a]), ';'.join(selected[a:])])
    return rows


def generate_dependencies(subs, direct_names):
    pairs = [(row[1], row[0]) for row in subs]
    shared = random.sample(subs, SHARED_COUNT)
    # Parent already counts as one of the 3-15 distinct direct vendors.
    counts = [SHARED_MIN - 1] * SHARED_COUNT
    remaining = EXTRA_PAIRS - sum(counts)
    if not 0 <= remaining <= SHARED_COUNT * (SHARED_MAX - SHARED_MIN):
        raise ValueError('Shared dependency settings infeasible')
    while remaining:
        i = random.choice([i for i, n in enumerate(counts) if n < SHARED_MAX - 1])
        counts[i] += 1
        remaining -= 1
    for row, count in zip(shared, counts):
        candidates = [name for name in direct_names if name != row[1]]
        pairs.extend((name, row[0]) for name in random.sample(candidates, count))
    return pairs


def split_cell(value):
    return value.split(';') if value else []


def validate(direct, subs, systems, services, pairs, hotspots, critical_hotspots):
    names = {r[0] for r in direct}
    subnames = {r[0] for r in subs}
    assert len(names) == DIRECT_COUNT and len(subnames) == SUBCONTRACTOR_COUNT and not names & subnames
    assert all(not r[1] for r in direct) and all(r[1] in names for r in subs)
    assert all(r[0].endswith(' Pvt Ltd') and r[9].endswith('.example') for r in direct + subs)
    for r in direct + subs:
        if r[10]:
            assert r[10] < r[11] and r[12] < r[13]
    codes = {r[0] for r in systems}
    assert len(codes) == SYSTEM_COUNT and len({r[0] for r in services}) == SERVICE_COUNT
    counts, critical_counts = Counter(), Counter()
    critical_total = sum(r[3] == 'CRITICAL' for r in systems)
    for r in systems:
        v, s = split_cell(r[7]), split_cell(r[8])
        assert SYSTEM_VENDOR_MIN <= len(v) <= SYSTEM_VENDOR_MAX and len(v) == len(set(v))
        assert set(v) <= names and set(s) <= set(v) and len(s) <= 2
        counts.update(v)
        if r[3] == 'CRITICAL':
            critical_counts.update(v)
    for v in hotspots:
        assert HOTSPOT_MIN <= counts[v] / SYSTEM_COUNT <= HOTSPOT_MAX
    for v in critical_hotspots:
        assert critical_counts[v] / critical_total >= CRITICAL_HOTSPOT_SHARE
    for r in services:
        a, b = split_cell(r[8]), split_cell(r[9])
        assert ESSENTIAL_MIN <= len(a) <= ESSENTIAL_MAX and SUPPORTING_MIN <= len(b) <= SUPPORTING_MAX
        assert len(set(a + b)) == len(a + b) and set(a + b) <= codes
    assert len(pairs) == len(set(pairs)) == SUBCONTRACTOR_COUNT + EXTRA_PAIRS
    assert all(a in names and b in subnames and a != b for a, b in pairs)
    assert {(r[1], r[0]) for r in subs} <= set(pairs)
    degrees = Counter(b for a, b in pairs)
    shared = [n for n in degrees.values() if n > 1]
    assert len(shared) == SHARED_COUNT and all(SHARED_MIN <= n <= SHARED_MAX for n in shared)
    return counts, critical_counts, critical_total


def main():
    random.seed(SEED)
    direct = [vendor_row(i) for i in range(1, DIRECT_COUNT + 1)]
    direct_names = [r[0] for r in direct]
    subs = [vendor_row(DIRECT_COUNT + i, random.choice(direct_names)) for i in range(1, SUBCONTRACTOR_COUNT + 1)]
    systems, hotspots, critical_hotspots = generate_systems(direct_names)
    services = generate_services([r[0] for r in systems])
    pairs = generate_dependencies(subs, direct_names)
    counts, critical_counts, critical_total = validate(direct, subs, systems, services, pairs, hotspots, critical_hotspots)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    stats = []
    write_chunks('vendors', VENDOR_HEADER, [direct, subs], stats)
    write_chunks('systems', SYSTEM_HEADER, [systems], stats)
    write_chunks('services', SERVICE_HEADER, [services], stats)
    path = OUTPUT_DIR / 'vendor_dependencies.csv'
    path.write_bytes(csv_line(DEPENDENCY_HEADER) + b''.join(csv_line(r) for r in pairs))
    stats.append((path.name, len(pairs), path.stat().st_size))
    print('File counts (data rows exclude header; bytes include header):')
    for name, rows, size in stats:
        print(f'{name:28s} {rows:6,d} rows {size:10,d} bytes')
        if name != path.name:
            assert rows <= MAX_ROWS and size <= MAX_BYTES
    print('\nTotal edges:')
    print('Parent -> subcontractor:', len(subs))
    print('Additional shared fourth-party edges:', EXTRA_PAIRS)
    print('Vendor dependency edges:', len(pairs))
    print('System -> vendor:', sum(counts.values()))
    print('Single-point vendor edges (subset):', sum(len(split_cell(r[8])) for r in systems))
    print('Service -> essential system:', sum(len(split_cell(r[8])) for r in services))
    print('Service -> supporting system:', sum(len(split_cell(r[9])) for r in services))
    print(f'\nHotspots ({critical_total} critical systems):')
    for name in hotspots:
        flag = ' [CRITICAL HOTSPOT]' if name in critical_hotspots else ''
        print(f'{name}: {counts[name]} systems ({counts[name]/SYSTEM_COUNT:.1%}); {critical_counts[name]} critical ({critical_counts[name]/critical_total:.1%}){flag}')
    print('\nValidation passed.')


if __name__ == '__main__':
    main()
