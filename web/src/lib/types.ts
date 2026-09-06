/**
 * The API response shapes this slice needs, hand-written to match the Java
 * records they come from.
 *
 * Hand-written rather than generated from the OpenAPI document on purpose, for
 * now: the API publishes `/v3/api-docs`, so generation is available the moment
 * these drift often enough to be annoying. Until then a generator would add a
 * build step and a lot of machine-written types for the seven fields Task 7b
 * actually reads.
 */

export type Role = "OWNER" | "STAFF";

/** `POST /auth/login`, `/auth/register` and `/auth/change-password`. */
export type AuthResponse = {
  token: string;
  expiresAt: string;
  userId: number;
  name: string;
  email: string;
  role: Role;
  businessId: number;
  businessName: string;
  /**
   * True only from `/auth/login`, and only for a staff member who has not yet
   * replaced the temporary password their owner generated. While it is true
   * the API refuses every endpoint but `/auth/change-password`, so the UI must
   * show the interstitial rather than the app.
   */
  mustChangePassword: boolean;
  /**
   * The business's own GST setting, readable by every member even though only
   * the owner may change it. Staff need it to render a correct GST line in
   * the builder's live preview before an invoice has ever been saved — see
   * `MeResponse` on the API side for why it lives here rather than behind the
   * owner-only `/settings`.
   */
  businessGstRegistered: boolean;
  /** A fraction, so 9% is `0.09`. Kept even while not registered. */
  businessGstRate: number;
};

/** `GET /auth/me` — the same identity, minus the token that carried it. */
export type MeResponse = Omit<AuthResponse, "token" | "expiresAt">;

/** The five states of Product Scope §4, in lifecycle order. */
export type InvoiceStatus =
  | "DRAFT"
  | "PENDING_APPROVAL"
  | "SENT"
  | "OVERDUE"
  | "PAID";

export type PaymentMethod = "BANK_TRANSFER" | "PAYNOW" | "CASH" | "CHEQUE";

/**
 * Money arrives from the API as a JSON number already rounded to two
 * decimals, never as a string. Kept as `number` here and formatted only at
 * the edge — see `lib/format.ts` for why no arithmetic happens in this app.
 */
export type Money = number;

export type Client = {
  id: number;
  name: string;
  contactPerson: string | null;
  email: string | null;
  phone: string | null;
  address: string | null;
  uen: string | null;
  paymentNotes: string | null;
  archived: boolean;
};

/** `GET /clients` and `GET /invoices` both answer with a Spring `Page`. */
export type Page<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
};

/** One row of `GET /invoices` — deliberately less than the full invoice. */
export type InvoiceSummary = {
  id: number;
  number: string;
  status: InvoiceStatus;
  clientId: number;
  clientName: string;
  issueDate: string;
  dueDate: string;
  total: Money;
  balance: Money;
};

export type LineItem = {
  id: number | null;
  description: string;
  quantity: number;
  unitPrice: Money;
  lineTotal: Money;
};

/** The bill-to block on the document (ADR-0011). */
export type ClientSummary = {
  id: number;
  name: string;
  address: string | null;
  contactPerson: string | null;
  email: string | null;
  uen: string | null;
  paymentNotes: string | null;
};

/** The letterhead — the document's "from" block (ADR-0011). */
export type BusinessSummary = {
  name: string;
  address: string | null;
  uen: string | null;
  gstRegistered: boolean;
};

export type Invoice = {
  id: number;
  number: string;
  status: InvoiceStatus;
  client: ClientSummary;
  business: BusinessSummary;
  issueDate: string;
  dueDate: string;
  lineItems: LineItem[];
  subtotal: Money;
  /** Null when the business does not charge GST — the document omits the line. */
  gstRate: number | null;
  gst: Money;
  total: Money;
  amountPaid: Money;
  balance: Money;
  rejectionNote: string | null;
  sentAt: string | null;
  createdAt: string;
};

export type Payment = {
  id: number;
  amount: Money;
  paidAt: string;
  method: PaymentMethod;
  note: string | null;
  recordedBy: { id: number; name: string };
};

/** The body of `POST`/`PUT /invoices`. */
export type InvoiceInput = {
  clientId: number;
  issueDate?: string;
  dueDate?: string;
  lineItems: { description: string; quantity: string; unitPrice: string }[];
};

/** `GET`/`PUT /settings` — owner only. */
export type SettingsResponse = {
  id: number;
  name: string;
  address: string | null;
  uen: string | null;
  gstRegistered: boolean;
  gstRate: number;
  defaultPaymentTermsDays: number;
};

/** `GET /dashboard` — owner only (Product Scope §2). */
export type Dashboard = {
  /** Remaining balance across every issued, unpaid invoice (SENT + OVERDUE). */
  outstandingTotal: Money;
  overdueAmount: Money;
  overdueCount: number;
  /** Payments actually received this calendar month — not what was invoiced. */
  revenueThisMonth: Money;
  awaitingApprovalCount: number;
  awaitingApprovalQueue: InvoiceSummary[];
};

/** A row of `GET /team` — owner only. */
export type StaffMember = {
  id: number;
  name: string;
  email: string;
  role: Role;
  active: boolean;
  createdAt: string;
  invoicesCreated: number;
  /** Latest of the invoices they created or sent; null if they have done neither. */
  lastActive: string | null;
};

/**
 * `POST /team`. The temporary password is returned exactly once and is never
 * stored in readable form — if it is lost, the only way back is a new account.
 * The UI must show it plainly and say so.
 */
export type CreatedStaff = {
  id: number;
  name: string;
  email: string;
  active: boolean;
  createdAt: string;
  temporaryPassword: string;
};
