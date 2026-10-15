"use client";

import { useEffect, useState } from "react";
import { api, useLoad } from "../../lib/api.ts";
import { equipmentLabel } from "../../lib/progress.ts";
import type { Equipment, Job, Recipe } from "../../lib/types.ts";

export default function NewJobPage() {
  const recipes = useLoad<Recipe[]>("/api/recipes");
  const equipment = useLoad<Equipment[]>("/api/equipment");
  // 화면을 열 때 한 번 만든다 — 같은 화면에서 두 번 눌러도 잡은 하나다(Idempotency-Key).
  const [key, setKey] = useState("");
  useEffect(() => setKey(crypto.randomUUID()), []);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function toggle(id: number) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const recipeId = Number(new FormData(e.currentTarget).get("recipeId"));
    setBusy(true);
    try {
      const job = await api<Job>("/api/jobs", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: JSON.stringify({ recipeId, equipmentIds: [...selected] }),
      });
      window.location.href = `/jobs/${job.id}`;
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  const active = recipes.data?.filter((r) => r.status === "ACTIVE") ?? [];

  return (
    <>
      <h1>배포 시작</h1>
      {(recipes.error || equipment.error) && <p className="error">{recipes.error ?? equipment.error}</p>}
      <form className="panel" onSubmit={submit}>
        <label>
          레시피(승인된 것만)
          <select name="recipeId" required className="mono">
            {active.map((r) => (
              <option key={r.id} value={r.id}>{r.partNo} v{r.version}</option>
            ))}
          </select>
        </label>
        <h2>설비</h2>
        <div className="checks">
          {equipment.data?.map((eq) => (
            <label key={eq.id}>
              <input
                type="checkbox"
                checked={selected.has(eq.id)}
                disabled={eq.status === "MAINTENANCE"}
                onChange={() => toggle(eq.id)}
              />
              <span className="mono">{eq.code}</span>
              {eq.status !== "RUNNING" && <span className="muted">{equipmentLabel(eq.status)}</span>}
            </label>
          ))}
        </div>
        {error && <p className="error" role="alert">{error}</p>}
        <div className="actions">
          <button type="submit" disabled={busy || !key || selected.size === 0 || active.length === 0}>
            {selected.size}대에 배포
          </button>
        </div>
      </form>
    </>
  );
}
