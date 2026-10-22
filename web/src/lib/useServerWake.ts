import { useEffect, useState } from "react";
import { pingApi } from "./api";
import { type ServerState, watchServer } from "./serverWake";

/** The API's wake state, for the signed-out screens. See {@link watchServer}. */
export function useServerWake(): ServerState {
  const [state, setState] = useState<ServerState>("ready");

  useEffect(() => {
    const aborter = new AbortController();
    void watchServer({ ping: pingApi, report: setState, signal: aborter.signal });
    return () => aborter.abort();
  }, []);

  return state;
}
