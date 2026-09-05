import { useState } from "react";
import { toast } from "sonner";
import { ApiError } from "@/lib/api";
import type { Client } from "@/lib/types";
import { useDebounced } from "@/lib/hooks";
import { EmptyState, Loading, LoadError, PageHeader } from "@/components/Page";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ClientFormDialog } from "./ClientFormDialog";
import { useClientsList, useDeleteClient, useUpdateClient } from "./useClients";

type Tab = "active" | "archived";

/**
 * The client list: search, an Active/Archived split, and the actions that
 * cover the whole lifecycle (create, edit, archive/restore, delete).
 *
 * <p>Archiving and restoring both go through `useUpdateClient` — a `PUT` of
 * the whole client with `archived` flipped — because that is the only
 * archive mechanism the API offers (Product Scope §5.2).
 */
export function ClientsScreen() {
  const [tab, setTab] = useState<Tab>("active");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const debouncedSearch = useDebounced(search, 300);

  // A new tab or search term changes which rows exist at all, so the filter
  // always starts back at the first page — staying on page 3 of a filter that
  // now has one page would just show nothing.
  //
  // Done in the handlers rather than in an effect on [tab, debouncedSearch].
  // An effect runs *after* the render that changed the filter, so there would
  // be one render holding the new filter and the old page, and the query for
  // that combination would actually be sent before the reset landed.
  function changeTab(next: Tab) {
    setTab(next);
    setPage(0);
  }

  function changeSearch(next: string) {
    setSearch(next);
    setPage(0);
  }

  // Kept as one object, rather than destructured into separate variables:
  // `isError` and `error` are two views of the same query state, and
  // TypeScript only knows `error` is non-null on the branch where `isError`
  // is true when both are read off the same object.
  const clients = useClientsList({
    archived: tab === "archived",
    q: debouncedSearch,
    page,
  });

  const [formOpen, setFormOpen] = useState(false);
  const [editingClient, setEditingClient] = useState<Client | null>(null);
  const [deletingClient, setDeletingClient] = useState<Client | null>(null);

  const updateClient = useUpdateClient();
  const deleteClient = useDeleteClient();

  function openCreate() {
    setEditingClient(null);
    setFormOpen(true);
  }

  function openEdit(client: Client) {
    setEditingClient(client);
    setFormOpen(true);
  }

  async function toggleArchived(client: Client) {
    // A full replace, per the API: everything the client already has, with
    // only `archived` changed.
    const { id, ...rest } = client;
    try {
      await updateClient.mutateAsync({ id, input: { ...rest, archived: !client.archived } });
      toast.success(client.archived ? "Client restored." : "Client archived.");
    } catch (caught) {
      toast.error((caught as ApiError).message);
    }
  }

  async function confirmDelete() {
    if (!deletingClient) return;
    try {
      await deleteClient.mutateAsync(deletingClient.id);
      setDeletingClient(null);
      toast.success("Client deleted.");
    } catch (caught) {
      // 409 client-has-invoices carries the API's own next step ("archive it
      // instead") in the message — that is more useful than a generic
      // failure, so it goes to the user verbatim.
      setDeletingClient(null);
      toast.error((caught as ApiError).message);
    }
  }

  return (
    <div>
      <PageHeader title="Clients" actions={<Button onClick={openCreate}>New client</Button>} />

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <Tabs value={tab} onValueChange={(value) => changeTab(value as Tab)}>
          <TabsList>
            <TabsTrigger value="active">Active</TabsTrigger>
            <TabsTrigger value="archived">Archived</TabsTrigger>
          </TabsList>
        </Tabs>

        <div className="w-full sm:w-56">
          <Label htmlFor="client-search" className="sr-only">
            Search clients
          </Label>
          <Input
            id="client-search"
            type="search"
            placeholder="Search clients…"
            value={search}
            onChange={(event) => changeSearch(event.target.value)}
          />
        </div>
      </div>

      {clients.isPending ? <Loading label="Loading clients…" /> : null}

      {clients.isError ? (
        <LoadError error={clients.error} onRetry={() => clients.refetch()} />
      ) : null}

      {clients.data && clients.data.content.length === 0 ? (
        tab === "active" ? (
          <EmptyState
            title="No clients yet."
            description="Add your first client to start billing them."
            action={<Button onClick={openCreate}>New client</Button>}
          />
        ) : (
          <EmptyState title="No archived clients." />
        )
      ) : null}

      {clients.data && clients.data.content.length > 0 ? (
        <>
          {/* The Table component already scrolls its own overflow (see
              ui/table.tsx); the min-width is what keeps five columns from
              collapsing into unreadable slivers at 375px instead of just
              scrolling. */}
          <Table className="min-w-[640px]">
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Contact</TableHead>
                <TableHead>Email</TableHead>
                <TableHead>Phone</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {clients.data.content.map((client) => (
                <TableRow key={client.id}>
                  <TableCell className="font-medium text-app-text">{client.name}</TableCell>
                  <TableCell>{client.contactPerson ?? "—"}</TableCell>
                  <TableCell>{client.email ?? "—"}</TableCell>
                  <TableCell>{client.phone ?? "—"}</TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="sm" onClick={() => openEdit(client)}>
                        Edit
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        // Shared across every row, since it is one mutation
                        // instance: archiving one client briefly disables the
                        // button on all of them rather than just the one
                        // being archived. Simple, and the request is quick
                        // enough that it is not worth tracking per-row state
                        // for.
                        disabled={updateClient.isPending}
                        onClick={() => toggleArchived(client)}
                      >
                        {client.archived ? "Restore" : "Archive"}
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        className="text-destructive"
                        onClick={() => setDeletingClient(client)}
                      >
                        Delete
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>

          {clients.data.totalPages > 1 ? (
            <div className="mt-4 flex items-center justify-between">
              <Button
                variant="outline"
                size="sm"
                disabled={clients.data.first}
                onClick={() => setPage(page - 1)}
              >
                Previous
              </Button>
              <p className="text-sm text-app-muted">
                Page {clients.data.number + 1} of {clients.data.totalPages}
              </p>
              <Button
                variant="outline"
                size="sm"
                disabled={clients.data.last}
                onClick={() => setPage(page + 1)}
              >
                Next
              </Button>
            </div>
          ) : null}
        </>
      ) : null}

      <ClientFormDialog open={formOpen} onOpenChange={setFormOpen} client={editingClient} />

      <AlertDialog
        open={deletingClient !== null}
        onOpenChange={(open) => {
          if (!open) setDeletingClient(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {deletingClient?.name}?</AlertDialogTitle>
            <AlertDialogDescription>
              This cannot be undone. Clients with invoices cannot be deleted — archive them
              instead.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                // The primitive closes the dialog on any click by default;
                // that has to wait for the request to actually finish; the
                // mutation's own onSuccess/onError above are what close it.
                event.preventDefault();
                confirmDelete();
              }}
              disabled={deleteClient.isPending}
            >
              {deleteClient.isPending ? "Deleting…" : "Delete"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
