# Ponytail Audit — Invoicely Phase 1 (Tasks 1–6)

Whole-repo over-engineering audit, read-only. Scope: `src/main/java`, `src/test/java`,
`pom.xml`, config/resources, CI workflows. Cross-checked against
`docs/Invoice_Product_Scope.md` and `docs/Invoice_Tech_Scope.md` so nothing the
scope explicitly calls for (Problem Details hierarchy, DTO/service/repository
split, role-vs-state separation, `CurrentRequest`, business-scoped lookups,
etc.) is flagged.

**Summary:** 6 findings — 2 HIGH, 1 MEDIUM, 3 LOW. This is an unusually lean,
disciplined codebase: nearly every class carries a doc comment justifying its
existence against a simpler alternative, and most of those justifications hold
up under scrutiny. What's left to cut is a small handful of genuinely unused
methods (verified by exhaustive grep across `src/main` and `src/test`) plus one
config file that outlived the reason it was split out. No dependency is
removable — every one in `pom.xml` is named in Tech Scope's Task 1 Initializr
list or actively exercised. Estimated: **-85 lines, -0 deps**.

## High severity

1. `delete:` `InvoiceStatus.isIssued()` is dead code — no caller anywhere in
   `src/main`. Its own Javadoc claims it decides "the GST rate is now fixed and
   line items are no longer editable," but those checks are actually done by
   `Invoice.hasBeenSent()` (in `InvoiceTotals`/`PaymentService`) and
   `status != DRAFT` (in `InvoiceService.requireDraft`). Its only exerciser is
   its own unit test — the classic sign of a method built for a use that never
   arrived. Delete the method and the one test that only tests it in isolation.
   [src/main/java/com/invoicely/domain/InvoiceStatus.java:69,
   src/test/java/com/invoicely/domain/InvoiceStatusTest.java:92]

2. `delete:` `CurrentRequest.role()` is dead code — zero callers in `src/main`
   or `src/test` (confirmed by grepping `role()` across the whole tree). Its
   Javadoc explains it exists for "places that need to describe the caller
   rather than gate them," but no such place exists in Phase 1: every role
   decision goes through `@PreAuthorize`, exactly as the same Javadoc says it
   should. Delete the method (and the now-unused `Role` import if nothing else
   in the file needs it).
   [src/main/java/com/invoicely/web/CurrentRequest.java:55-61]

## Medium severity

3. `delete:` `LineItem.setDescription/setQuantity/setUnitPrice` are unused
   setters — grepped with zero callers outside their own declarations. Line
   items are never mutated in place: `InvoiceService.update` always calls
   `invoice.clearLineItems()` then rebuilds via `addLineItem(...)` (the
   package-private constructor), which is also what `Invoice`'s own class
   comment says is the only way line items change. These three setters are
   unused flexibility left over from before that replace-wholesale pattern was
   settled. Removing them also makes `LineItem` read as effectively immutable
   after construction, which is easier for a junior to reason about than
   "mutable, but nothing calls the mutators."
   [src/main/java/com/invoicely/domain/LineItem.java:76-94]

## Low severity

4. `delete:` `Invoice.getSentBy()` is an unused getter — `setSentBy` is called
   from `InvoiceLifecycleService.send`, but nothing ever reads the value back;
   `TeamService`'s "last sent" figure comes from the separate
   `InvoiceRepository.lastSentByUser` aggregate query, not this accessor.
   Trivial to remove; trivial to restore if a later phase needs it.
   [src/main/java/com/invoicely/domain/Invoice.java:249-251]

5. `shrink:` `SchedulingConfig` is a whole file for one annotation. Its own
   comment says it was split out so "Task 5's overdue job doesn't need to
   touch a file other in-flight work also depends on" — a parallel-authorship
   concern from mid-Phase-1 that no longer applies now that Tasks 1–6 are
   merged. Fold `@EnableScheduling` onto `InvoicelyApplication` and delete the
   file:
   ```java
   @EnableScheduling
   @SpringBootApplication
   public class InvoicelyApplication {
       public static void main(String[] args) {
           SpringApplication.run(InvoicelyApplication.class, args);
       }
   }
   ```
   [src/main/java/com/invoicely/SchedulingConfig.java,
   src/main/java/com/invoicely/InvoicelyApplication.java]

6. `delete:` `LineItem.getInvoice()` and `LineItem.getPosition()` are unused
   JavaBean accessors — JPA needs neither to maintain the bidirectional
   association or honor `@OrderBy("position ASC")` on `Invoice.lineItems`
   (both work off the mapped field, not a getter). Lowest-value finding here:
   they're cheap, conventional, and harmless to keep if it reads more
   "complete" as a class — cut only if tidying this file anyway.
   [src/main/java/com/invoicely/domain/LineItem.java:68-70, 96-98]

---

net: -85 lines, -0 deps possible.
