import assert from "node:assert/strict";
import { test } from "node:test";
import { equipmentLabel, isJobSettled, mergeSnapshot, progress, statusLabel } from "./progress.ts";
import type { Job } from "./types.ts";

test("progress counts terminal tasks as done and floors percent", () => {
  const p = progress([
    { status: "SUCCEEDED" },
    { status: "FAILED" },
    { status: "CANCELLED" },
    { status: "CLAIMED" },
    { status: "PENDING" },
    { status: "PENDING" },
  ]);
  assert.deepEqual(p, { total: 6, done: 3, succeeded: 1, failed: 1, cancelled: 1, running: 1, pending: 2, percent: 50 });
  assert.equal(progress([{ status: "SUCCEEDED" }, { status: "PENDING" }, { status: "PENDING" }]).percent, 33);
});

test("progress of empty job is zero, not NaN", () => {
  assert.equal(progress([]).percent, 0);
});

test("settled statuses are exactly the three terminal job statuses", () => {
  assert.deepEqual(
    (["PENDING", "RUNNING", "SUCCEEDED", "PARTIAL", "FAILED"] as const).filter(isJobSettled),
    ["SUCCEEDED", "PARTIAL", "FAILED"],
  );
});

test("labels are Korean and unknown codes pass through", () => {
  assert.equal(statusLabel("PARTIAL"), "일부 실패");
  assert.equal(statusLabel("CLAIMED"), "적용 중");
  assert.equal(equipmentLabel("RUNNING"), "가동");
  assert.equal(statusLabel("RUNNING"), "진행 중");
  assert.equal(statusLabel("SOMETHING_NEW"), "SOMETHING_NEW");
});

const job: Job = {
  id: 7,
  recipeId: 1,
  status: "RUNNING",
  requestedBy: 2,
  tasks: [
    { id: 1, equipmentId: 10, equipmentCode: "EQ-01", status: "CLAIMED", attempt: 1 },
    { id: 2, equipmentId: 11, equipmentCode: "EQ-02", status: "PENDING", attempt: 0 },
  ],
};

test("snapshot merges by equipment code and keeps task ids", () => {
  const merged = mergeSnapshot(job, {
    jobId: 7,
    status: "PARTIAL",
    tasks: [{ equipmentCode: "EQ-02", status: "FAILED", attempt: 3, lastError: "TIMEOUT" }],
  });
  assert.equal(merged.status, "PARTIAL");
  assert.deepEqual(merged.tasks![0], job.tasks![0]);
  assert.deepEqual(merged.tasks![1], { id: 2, equipmentId: 11, equipmentCode: "EQ-02", status: "FAILED", attempt: 3, lastError: "TIMEOUT" });
});

test("snapshot of another job is ignored", () => {
  assert.equal(mergeSnapshot(job, { jobId: 8, status: "FAILED", tasks: [] }), job);
});

test("snapshot with null status keeps job status", () => {
  assert.equal(mergeSnapshot(job, { jobId: 7, status: null, tasks: [] }).status, "RUNNING");
});
