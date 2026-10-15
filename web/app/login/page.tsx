"use client";

import { useState } from "react";
import { api } from "../lib/api.ts";

export default function LoginPage() {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setBusy(true);
    setError(null);
    try {
      await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ username: form.get("username"), password: form.get("password") }),
      });
      window.location.href = "/equipment";
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <>
      <h1>로그인</h1>
      <form className="stack panel" onSubmit={submit}>
        <label>
          아이디
          <input name="username" autoComplete="username" required />
        </label>
        <label>
          비밀번호
          <input name="password" type="password" autoComplete="current-password" required />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button type="submit" disabled={busy}>로그인</button>
      </form>
    </>
  );
}
