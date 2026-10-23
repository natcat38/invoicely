import { useState } from "react";
import { MenuIcon } from "lucide-react";
import { NavLink, Outlet, useLocation } from "react-router";
import { useAuth } from "@/auth/useAuth";
import type { Role } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetDescription, SheetTitle, SheetTrigger } from "@/components/ui/sheet";

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
 * The app layer: a fixed dark sidebar from `md` up, and below that a compact
 * top bar whose menu button opens the same sidebar content in a sheet. The
 * Design Direction asks for a "fixed dark sidebar"; on a phone that would eat
 * the screen, so the bar costs 56px and the nav is one tap away (decided
 * 2026-10-23, see the Design Direction). Intentionally quiet — every bit of
 * this product's character lives in the invoice document inside this frame.
 */
export function AppShell() {
  const { session } = useAuth();
  const user = session!.user;
  const items = NAV_ITEMS.filter((item) => !item.role || item.role === user.role);
  const [menuOpen, setMenuOpen] = useState(false);
  const { pathname } = useLocation();
  // "/invoices/2" and "/invoices/new" both belong to the Invoices section.
  const section = items.find((item) => pathname === item.to || pathname.startsWith(`${item.to}/`));

  return (
    <div className="min-h-dvh md:grid md:grid-cols-[16rem_1fr]">
      {/* First tab stop on every page: jumps past the nav to the content.
          Hidden until it has focus. It moves focus itself instead of using
          href="#main", which would add "#main" to the URL and a history entry. */}
      <a
        href="#main"
        onClick={(event) => {
          event.preventDefault();
          document.getElementById("main")?.focus();
        }}
        className="fixed top-2 left-2 z-50 -translate-y-20 rounded-md bg-app-surface px-4 py-3 text-sm font-medium text-app-text shadow-md outline-none focus:translate-y-0 focus:ring-2 focus:ring-app-accent"
      >
        Skip to content
      </a>
      {/* Phones. `sticky` keeps the menu reachable on a long page without
          taking the page out of the flow the way `fixed` would. */}
      <div className="sticky top-0 z-30 flex h-14 items-center justify-between bg-app-sidebar px-4 text-sidebar-foreground md:hidden">
        <div className="flex items-baseline gap-3">
          <p className="font-serif text-xl text-white">Invoicely</p>
          {section ? <p className="text-sm text-sidebar-foreground/70">{section.label}</p> : null}
        </div>
        <Sheet open={menuOpen} onOpenChange={setMenuOpen}>
          <SheetTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Menu"
              className="text-sidebar-foreground hover:bg-sidebar-accent hover:text-white"
            >
              <MenuIcon />
            </Button>
          </SheetTrigger>
          <SheetContent className="bg-app-sidebar p-4 text-sidebar-foreground md:hidden">
            <SheetTitle className="sr-only">Menu</SheetTitle>
            <SheetDescription className="sr-only">Navigation and account</SheetDescription>
            <SidebarContent items={items} onNavigate={() => setMenuOpen(false)} />
          </SheetContent>
        </Sheet>
      </div>

      {/* From `md`: pinned to the viewport so the nav and Sign out stay in
          view however tall the page is. `self-start` stops the grid from
          stretching it to the page's height, which would defeat sticky. */}
      <aside className="hidden bg-app-sidebar p-4 text-sidebar-foreground md:sticky md:top-0 md:flex md:h-dvh md:flex-col md:self-start md:overflow-y-auto">
        <SidebarContent items={items} />
      </aside>

      {/* min-w-0 so a wide table inside scrolls in its own container instead of
          stretching the grid column and scrolling the whole page sideways —
          the Design Direction's quality floor asks for exactly that. */}
      <main id="main" tabIndex={-1} className="min-w-0 p-6 outline-none">
        <Outlet />
      </main>
    </div>
  );
}

/**
 * Brand, nav, and the signed-in user. Rendered twice - in the desktop sidebar
 * and in the phone menu sheet - so the two can never drift apart.
 */
function SidebarContent({ items, onNavigate }: { items: NavItem[]; onNavigate?: () => void }) {
  const { session, signOut } = useAuth();
  const user = session!.user;

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-6">
      <div>
        <p className="font-serif text-xl text-white">Invoicely</p>
        <p className="truncate text-sm text-sidebar-foreground/70" title={user.businessName}>
          {user.businessName}
        </p>
      </div>

      <nav aria-label="Main" className="flex flex-col gap-1">
        {items.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            onClick={onNavigate}
            className={({ isActive }) =>
              [
                "rounded-md px-3 py-3 text-sm transition-colors md:py-2",
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
    </div>
  );
}
