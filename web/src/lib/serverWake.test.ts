import { describe, expect, it } from "vitest";
import { type ServerState, watchServer } from "./serverWake";

/** Runs watchServer with a scripted ping and returns every state it reported. */
async function run(answers: boolean[], options: { giveUpMs?: number; slowMs?: number } = {}) {
  const states: ServerState[] = [];
  const remaining = [...answers];
  await watchServer({
    ping: async () => remaining.shift() ?? false,
    report: (state) => states.push(state),
    signal: new AbortController().signal,
    retryMs: 1,
    slowMs: 50,
    ...options,
  });
  return states;
}

describe("watchServer", () => {
  it("says nothing but ready when the API answers straight away", async () => {
    // A warm API must not flash a "waking up" notice at every visitor.
    expect(await run([true])).toEqual(["ready"]);
  });

  it("shows the waking notice while probes fail, then ready once one succeeds", async () => {
    expect(await run([false, false, true])).toEqual(["waking", "waking", "ready"]);
  });

  it("shows the waking notice for a probe that is merely slow", async () => {
    const states: ServerState[] = [];
    await watchServer({
      ping: () => new Promise((resolve) => setTimeout(() => resolve(true), 120)),
      report: (state) => states.push(state),
      signal: new AbortController().signal,
      slowMs: 30,
    });
    expect(states).toEqual(["waking", "ready"]);
  });

  it("gives up and says the server is unresponsive instead of polling forever", async () => {
    const states = await run([], { giveUpMs: 20 });
    expect(states[states.length - 1]).toBe("unresponsive");
    expect(states).toContain("waking");
  });

  it("stops reporting once the screen is gone", async () => {
    const aborter = new AbortController();
    const states: ServerState[] = [];
    await watchServer({
      ping: async () => {
        aborter.abort();
        return true;
      },
      report: (state) => states.push(state),
      signal: aborter.signal,
      slowMs: 10,
    });
    expect(states).toEqual([]);
  });
});
