package com.rollout.api

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * 요청 본문 상한 64 KiB(불변 조건 4). `Content-Length`는 클라이언트가 적는 값이라 `Transfer-Encoding: chunked`에
 * 뚫린다 — 입력 스트림을 감싸 **실제로 읽은 바이트**를 세고, 상한을 넘는 순간 읽기를 실패시켜 파싱(=핸들러 호출)
 * 전에 끊는다. 413 응답은 [ApiExceptionHandler]가 이 요청 속성을 보고 만든다.
 *
 * ponytail: 폼·멀티파트는 스트림 밖(컨테이너 파라미터 파싱) 경로지만 둘 다 프로퍼티로 꺼 뒀고
 * `ConfigPinTest`가 그 값을, `ErrorHandlingTest`가 동작을 지킨다 — 켤 일이 생기면 그때 래퍼를 씌운다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class BodyLimitFilter : OncePerRequestFilter() {

	override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
		chain.doFilter(LimitedRequest(request), response)
	}

	private class LimitedRequest(private val request: HttpServletRequest) : HttpServletRequestWrapper(request) {
		private val stream by lazy { LimitedStream(request) }

		override fun getInputStream(): ServletInputStream = stream
	}

	private class LimitedStream(private val request: HttpServletRequest) : ServletInputStream() {
		private val delegate = request.inputStream
		private var count = 0L

		private fun counted(n: Int): Int {
			if (n > 0) {
				count += n
				if (count > MAX_BYTES) {
					request.setAttribute(EXCEEDED, true)
					throw IOException("요청 본문 상한 초과")
				}
			}
			return n
		}

		override fun read(): Int {
			val b = delegate.read()
			if (b >= 0) counted(1)
			return b
		}

		override fun read(b: ByteArray, off: Int, len: Int): Int = counted(delegate.read(b, off, len))

		override fun isFinished() = delegate.isFinished

		override fun isReady() = delegate.isReady

		override fun setReadListener(listener: ReadListener) = delegate.setReadListener(listener)
	}

	companion object {
		const val MAX_BYTES = 64L * 1024
		const val EXCEEDED = "com.rollout.api.BodyLimitFilter.EXCEEDED"
	}
}
