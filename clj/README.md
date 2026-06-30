# hakken CLJ runtime

This directory replaces the old `lg_hakken` Python LangGraph scaffold.

Implemented handlers:

- `health`
- `discovery`
- `phase_promotion`
- `trend_scan`
- `supplier_search`
- `quality_eval`
- `phase_router`

The implementation preserves the product discovery decision contract:
trend products, supplier candidates, grade estimation, phase routing
(`dropship` / `import` / `oem`), catalog registration IDs, announcements, and
phase promotion thresholds. External XRPC calls are represented by deterministic
payload/stub inputs so the runtime is portable and testable.

Run tests:

```sh
clojure -M:test
```

Run server:

```sh
LANGSERVER_PORT=8080 clojure -M -m hakken.server
```
