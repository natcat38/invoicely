import { NavLink, Outlet } from "react-router";
import { useAuth } from "@/auth/useAuth";
import type { Role } from "@/lib/types";
import { Button } from "@/components/ui/button";

type NavItem = { to: string; label: string; role?: Role };

/**
 * The sidebar's items, in the Design Direction's order.
 *
 * `role: "OWNER"` hides an item from staff. That is presentation only — the
 * API refuses these endpoints for staff regardless (Product Scope §3), and a
 * hidden link is a courtesy, never a control.
 */
const NAV_ITEMS: NavItem[] = [
  { to: "/dashboard", label: "Dashboard", role: "OWNER" },
  { to: "/invoices", label: "Invoices" },
  { to: "/clients", label: "Clients" },
  { to: "/team", label: "Team", role: "OWNER" },
  { to: "/settings", label: "Settings", role: "OWNER" },
];

/**
 * The app layer: a fixed dark sidebar and a content area, per the Design
 * Direction. Intentionally quiet — every bit of this product's character lives
 * in the invoice document that Task 8 renders inside this frame.
 */
export function AppShell() {
  const { session, signOut } = useAuth();
  const user = session!.user;
  const items = NAV_ITEMS.filter((item) => !item.role || item.role === user.role);

  return (
    <div className="min-h-dvh md:grid md:grid-cols-[16rem_1fr]">
      <aside className="flex flex-col gap-6 bg-app-sidebar p-4 text-sidebar-foreground md:min-h-dvh">
        <div>
          <p className="font-serif text-xl text-white">Invoicely</p>
          <p className="truncate text-sm text-sidebar-foreground/70" title={user.businessName}>
            {user.businessName}
          </p>
        </div>

        <nav aria-label="Main" className="flex flex-wrap gap-1 md:flex-col">
          {items.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                [
                  "rounded-md px-3 py-2 text-sm transition-colors",
                  isActive
                    ? "bg-sidebar-accent text-sidebar-accent-foreground"
                    : "text-sidebar-foreground/80 hover:bg-sidebar-accent/60 hover:text-white",
                ].join(" ")
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="mt-auto space-y-2 border-t border-sidebar-border pt-4">
          {/* px-3 matches the nav links' and the sign-out button's padding, so
              the name lines up with them rather than sitting 12px to their
              left against the sidebar edge. */}
          <div className="px-3 text-sm">
            <p className="truncate text-white" title={user.name}>
              {user.name}
            </p>
            {/* Staff need to know which hat they are wearing: it explains why
                they can draft an invoice but not send it. */}
            <p className="text-xs text-sidebar-foreground/70">
              {user.role === "OWNER" ? "Owner" : "Staff"}
            </p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={signOut}
            className="w-full justify-start text-sidebar-foreground/80 hover:bg-sidebar-accent/60 hover:text-white"
          >
            Sign out
          </Button>
        </div>
      </aside>

      {/* min-w-0 so a wide table inside scrolls in its own container instead of
          stretching the grid column and scrolling the whole page sideways —
          the Design Direction's quality floor asks for exactly that. */}
      <main className="min-w-0 p-6">
        <Outlet />
      </main>
    </div>
  );
}
