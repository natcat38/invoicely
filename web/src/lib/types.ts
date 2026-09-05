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
