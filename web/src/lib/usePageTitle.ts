import { useEffect } from "react";

/**
 * Puts the page's name in the browser tab and history, so several Invoicely
 * tabs (or a Back-button list) can be told apart: "Invoices - Invoicely".
 */
export function usePageTitle(title: string) {
  useEffect(() => {
    document.title = `${title} - Invoicely`;
  }, [title]);
}
