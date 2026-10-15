"use client";

import { useLoad } from "../lib/api.ts";
import { equipmentLabel } from "../lib/progress.ts";
import type { Equipment } from "../lib/types.ts";

export default function EquipmentPage() {
  const { data, error } = useLoad<Equipment[]>("/api/equipment");
  return (
    <>
      <h1>설비</h1>
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr><th>설비 코드</th><th>모델</th><th>상태</th><th>마지막 적용 레시피</th></tr>
        </thead>
        <tbody>
          {data?.map((e) => (
            <tr key={e.id}>
              <td className="mono">{e.code}</td>
              <td>{e.model}</td>
              <td><span className={`badge ${e.status === "RUNNING" ? "RUNNING-eq" : e.status}`}>{equipmentLabel(e.status)}</span></td>
              <td className="mono">{e.lastApplied ?? <span className="muted">없음</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {data?.length === 0 && <p className="muted">등록된 설비가 없습니다.</p>}
    </>
  );
}
