import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router";
import { AuthProvider } from "@/auth/AuthProvider";
import { ApiError } from "@/lib/api";
import { App } from "./App";
import "./index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      /**
       * Never retry a request the API meant to refuse. The default retries
       * three times, which for a 403 means three identical refusals and a
       * three-times-slower error message — and against the login throttle it
       * would burn a caller's attempt budget on one wrong password.
       */
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
      // Invoices change when this user changes them, not on a timer, so
      // refetching on every window focus is noise. Screens that mutate data
      // invalidate their own queries instead.
      refetchOnWindowFocus: false,
    },
  },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      {/* AuthProvider inside the query client (it will use it later) but
          outside the router, so every route can read the session. */}
      <AuthProvider>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
