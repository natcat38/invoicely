# Invoicely

Invoicing for freelancers: clients, invoices with line items, payments, and a
dashboard of who owes what — with a strict invoice lifecycle
(`DRAFT → SENT → OVERDUE/PAID`) enforced server-side.

Built to demonstrate production-shaped backend work in **Java 21 / Spring Boot 3**:
JWT auth (Spring Security), PostgreSQL with Flyway migrations, Testcontainers
integration tests, and a minimal designed React UI in Phase 2.

> **Status:** planning complete, implementation starting. See
> [docs/Invoice_Product_Scope.md](docs/Invoice_Product_Scope.md),
> [docs/Invoice_Tech_Scope.md](docs/Invoice_Tech_Scope.md), and
> [docs/Invoice_Design_Direction.md](docs/Invoice_Design_Direction.md).

## Project knowledge

Domain concepts live in [`knowledge/`](knowledge/index.md) (OKF house standard,
validated in CI). Directory index: [FILE-MAP.md](FILE-MAP.md).
