package com.rollout.job

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** pgjdbc에 시각을 넘기는 형(timestamptz). 저장 값은 UTC로 정규화된다 — 표시 시간대는 화면 몫이다. */
fun Instant.toDb(): OffsetDateTime = atOffset(ZoneOffset.UTC)
