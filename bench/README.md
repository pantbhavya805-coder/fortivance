# Impact query benchmark

Loads a synthetic register of 5,000 vendors, 1,200 systems and 400 services into a separate copy of Fortivance
and measures the impact analysis. Results are in `results/`.

All data is fictional (invented names ending in "Pvt Ltd", domains ending in `.example`). Never run this against
production or your normal local data: the import never deletes, so the records would stay.

## Files

| File | Purpose |
|---|---|
| `generate_vendor_register.py` | Writes the CSVs to `bench/data/` (ignored by git). Standard library only, seed 42, so the output is the same on every run. |
| `generation_report.txt` | Output of the generator: row counts, link counts, the 20 hotspot vendors |
| `check_csv.py` | Independent check of the generated files against the import rules |
| `docker-compose.bench.yml` | Second stack with its own containers and database; AI key and mail blanked |
| `import.ps1` | Runs the import check on each file, then imports it, in dependency order |
| `explain.sql` | `EXPLAIN ANALYZE` of the three queries in `dependency/ImpactService.java` |

## Run it (PowerShell, from the repo root)

1. Generate and check the data:
   ```powershell
   python bench\generate_vendor_register.py
   python bench\check_csv.py bench\data
   ```
2. Stop the normal stack (frees ports 8080 and 3307; its data stays in its own volume) and start the benchmark stack:
   ```powershell
   docker compose stop
   docker compose -p fortivance-bench -f docker-compose.yml -f bench/docker-compose.bench.yml up -d --build
   ```
3. Enable an import account **in the benchmark database only**. The seeded `approver` account
   (password `Fortivance@2026`) was switched off by migration V4 and is marked deleted at every app startup, so run
   this again after each restart or rebuild of the benchmark app:
   ```powershell
   "UPDATE users SET active = TRUE, deleted_at = NULL WHERE username = 'approver';" | docker exec -i fortivance-bench-mysql sh -c 'mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE'
   ```
4. Import:
   ```powershell
   powershell -ExecutionPolicy Bypass -File bench\import.ps1
   ```
5. Measure. `explain.sql` runs for a subcontractor by default; change `SET @v = @sub;` to `@hot` or `@small` for the
   other two vendors:
   ```powershell
   Get-Content bench\explain.sql | docker exec -i fortivance-bench-mysql sh -c 'mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE'
   ```
   For API timings, sign in once and reuse the session; sending Basic credentials on every request adds about
   70 ms of password hashing per call.
6. Clean up and return to the normal stack:
   ```powershell
   docker compose -p fortivance-bench down -v
   docker compose start
   ```
   `-v` deletes the benchmark database volume; leave it out to keep the data for another run.
