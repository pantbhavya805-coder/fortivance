# Benchmark results: impact query at 5,000 vendors (25 Nov 2026)

Local Docker stack (project `fortivance-bench`), MySQL 8.4, current schema (single `parent_vendor_id`).

## Register loaded through the real CSV import

| File | Rows | Import time |
|---|---|---|
| vendors_01..03 | 1,500 direct + 3,500 subcontractors | 1.4 s + 1.3 s + 0.8 s |
| systems_01 | 1,200 systems, 13,094 system-vendor links | 2.8 s |
| services_01 | 400 services, 3,780 service-system links | 0.9 s |

Every file passed the check with 0 errors and 0 warnings.

## EXPLAIN ANALYZE (actual time of the top node)

| Vendor | Chain (recursive CTE) | Applications | Services |
|---|---|---|---|
| Subcontractor of the hotspot (id 1718) | 0.05 ms, 2 vendors | 2.3 ms, 480 rows | 9.1 ms, 1,513 rows |
| Hotspot, in 480 systems (id 24) | 0.05 ms, 1 vendor | 2.6 ms, 480 rows | 8.8 ms, 1,513 rows |
| Ordinary vendor (id 7) | 0.04 ms, 1 vendor | 0.13 ms, 11 rows | 0.27 ms, 35 rows |

Every step uses an index (PRIMARY, fk_avd_vendor, fk_sad_app); no table scans. Most of the time goes to sorting.

## API (GET /api/impact/vendors/{id}, median of 5)

- With a session: about 4 ms per call for all three vendors.
- With HTTP Basic on every request: 65 to 80 ms, almost all of it bcrypt password checking.
- 1,500 calls from a curl loop: 68.5 s, dominated by starting curl on Windows (about 45 ms each).

## Concentration baseline (one SQL query, current schema)

Systems, services, critical services and tightest tolerance for every direct vendor: about 175 ms (3 runs: 208,
179, 171 ms).

## Findings

1. The recursive CTE is not a bottleneck at this size: under 0.1 ms. Cost grows with the number of affected rows,
   not with the size of the register.
2. With one parent per vendor, concentration is a single GROUP BY (175 ms). An in-memory graph pays off only after
   shared subcontractors (vendor_dependencies, V10) make reach a many-path traversal.
3. Data caveat: services pick systems at random, so each hotspot reaches about 395 of 400 services and the ranking
   cannot tell them apart. For concentration testing, regenerate with services drawing systems from clusters
   (business lines) and critical services preferring critical systems.
4. Clients that call the API often (such as the planned MCP server) should keep a session instead of sending
   Basic credentials every time.
