"use client";

import { useState } from "react";
import { api, formatAt, useLoad, useMe } from "../lib/api.ts";
import { statusLabel } from "../lib/progress.ts";
import type { Recipe } from "../lib/types.ts";

// 목업 콘솔의 레시피 폼 수치 5필드(console-mock NUMERIC_FIELDS).
const PARAMS = [
  ["mold_temp_c", "금형 온도(℃)"],
  ["inject_pressure_mpa", "사출 압력(MPa)"],
  ["hold_pressure_mpa", "보압(MPa)"],
  ["cooling_sec", "냉각 시간(초)"],
  ["cycle_sec", "사이클(초)"],
] as const;

export default function RecipesPage() {
  const me = useMe();
  const { data, error, reload } = useLoad<Recipe[]>("/api/recipes");
  const [formError, setFormError] = useState<string | null>(null);

  async function create(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const f = new FormData(form);
    const params = Object.fromEntries(PARAMS.map(([k]) => [k, String(f.get(k))]));
    try {
      await api("/api/recipes", {
        method: "POST",
        body: JSON.stringify({ partNo: f.get("partNo"), version: Number(f.get("version")), params }),
      });
      form.reset();
      setFormError(null);
      reload();
    } catch (err) {
      setFormError((err as Error).message);
    }
  }

  return (
    <>
      <h1>레시피</h1>
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr><th>품번</th><th>버전</th><th>상태</th><th>작성</th></tr>
        </thead>
        <tbody>
          {data?.map((r) => (
            <tr key={r.id}>
              <td className="mono"><a href={`/recipes/${r.id}`}>{r.partNo}</a></td>
              <td className="mono">v{r.version}</td>
              <td><span className={`badge ${r.status}`}>{statusLabel(r.status)}</span></td>
              <td>{formatAt(r.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {(me?.role === "OPERATOR" || me?.role === "APPROVER") && (
        <>
          <h2>새 초안</h2>
          <form className="stack panel" onSubmit={create}>
            <label>품번<input name="partNo" className="mono" required /></label>
            <label>버전<input name="version" type="number" min={1} required /></label>
            {PARAMS.map(([key, label]) => (
              <label key={key}>{label}<input name={key} type="number" className="mono" required /></label>
            ))}
            {formError && <p className="error" role="alert">{formError}</p>}
            <button type="submit">초안 저장</button>
          </form>
        </>
      )}
    </>
  );
}
