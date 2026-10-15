"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { api, formatAt, useLoad, useMe } from "../../lib/api.ts";
import { statusLabel } from "../../lib/progress.ts";
import type { Recipe } from "../../lib/types.ts";

export default function RecipePage() {
  const { id } = useParams<{ id: string }>();
  const me = useMe();
  const { data: recipe, error, reload } = useLoad<Recipe>(`/api/recipes/${id}`);
  const [actionError, setActionError] = useState<string | null>(null);

  async function act(action: "approve" | "retire") {
    try {
      await api(`/api/recipes/${id}/${action}`, { method: "POST" });
      setActionError(null);
      reload();
    } catch (err) {
      setActionError((err as Error).message);
    }
  }

  // 승인·폐기 권한은 서버(Policy)가 강제한다. 화면은 APPROVER가 아니면 버튼을 숨길 뿐이다.
  const approver = me?.role === "APPROVER";

  return (
    <>
      <h1>레시피 <span className="mono">{recipe ? `${recipe.partNo} v${recipe.version}` : id}</span></h1>
      {error && <p className="error">{error}</p>}
      {recipe && (
        <>
          <p>
            <span className={`badge ${recipe.status}`}>{statusLabel(recipe.status)}</span>{" "}
            <span className="muted">작성 {formatAt(recipe.createdAt)}</span>
          </p>
          <table>
            <thead><tr><th>파라미터</th><th>값</th></tr></thead>
            <tbody>
              {Object.entries(recipe.params).map(([k, v]) => (
                <tr key={k}><td className="mono">{k}</td><td className="mono">{v}</td></tr>
              ))}
            </tbody>
          </table>
          {approver && (
            <div className="actions">
              {recipe.status === "DRAFT" && <button onClick={() => act("approve")}>승인</button>}
              {recipe.status === "ACTIVE" && <button className="secondary" onClick={() => act("retire")}>폐기</button>}
            </div>
          )}
          {actionError && <p className="error" role="alert">{actionError}</p>}
        </>
      )}
    </>
  );
}
