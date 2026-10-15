package com.rollout.api

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 에러 형식은 하나다(불변 조건 4) — 본문은 `{code, message}`뿐이고 스택트레이스·SQL·클래스명·제약 이름을
 * 싣지 않는다. 로그에도 예외 **클래스명**만 남긴다(프레임워크 메시지·거부된 값 금지).
 */
class ApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)

fun forbidden(code: String = "FORBIDDEN"): Nothing = throw ApiException(403, code, "권한이 없다")

fun notFound(): Nothing = throw ApiException(404, "NOT_FOUND", "대상을 찾을 수 없다")

fun conflict(code: String, message: String): Nothing = throw ApiException(409, code, message)

fun invalid(message: String = "요청이 올바르지 않다"): Nothing = throw ApiException(400, "INVALID_REQUEST", message)

/**
 * 계약의 `minLength`·`maxLength`를 요청 DTO의 `init`에서 강제한다(리뷰 B5). 실패는 역직렬화 예외로 감싸여
 * 아래 400 `INVALID_REQUEST` 경로로 온다 — DB 열 너비가 대신 막아 409가 되거나 빈 값이 저장되지 않게.
 */
fun requireText(value: String, max: Int) = require(value.isNotEmpty() && value.length <= max)

@RestControllerAdvice
@ConditionalOnProperty(name = ["mode"], havingValue = "api")
class ApiExceptionHandler {

	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(ApiException::class)
	fun api(e: ApiException) = body(e.status, e.code, e.message)

	/**
	 * 본문을 읽다 상한에 걸린 요청은 파싱 실패(400)의 옷을 입고 온다 — 상한 초과 표시가 있으면 413이다.
	 * Content-Type 누락·불일치(415)와 `Accept` 불일치(406)도 본문 규약을 지키지 않은 클라이언트 오류라 400으로 접는다.
	 */
	@ExceptionHandler(
		HttpMessageNotReadableException::class,
		MissingRequestHeaderException::class,
		MissingServletRequestParameterException::class,
		MethodArgumentTypeMismatchException::class,
		HttpMediaTypeNotSupportedException::class,
		HttpMediaTypeNotAcceptableException::class,
		IllegalArgumentException::class,
	)
	fun badRequest(request: HttpServletRequest) =
		tooLarge(request) ?: body(400, "INVALID_REQUEST", "요청이 올바르지 않다")

	@ExceptionHandler(NoResourceFoundException::class, EmptyResultDataAccessException::class)
	fun missing() = body(404, "NOT_FOUND", "대상을 찾을 수 없다")

	@ExceptionHandler(HttpRequestMethodNotSupportedException::class)
	fun method() = body(405, "METHOD_NOT_ALLOWED", "허용되지 않는 메서드다")

	@ExceptionHandler(DataIntegrityViolationException::class)
	fun constraint() = body(409, "CONSTRAINT_VIOLATION", "제약을 위반했다")

	@ExceptionHandler(Exception::class)
	fun internal(e: Exception, request: HttpServletRequest): ResponseEntity<Map<String, String>> {
		tooLarge(request)?.let { return it }
		log.error("요청 처리 실패: exception={} code=INTERNAL_ERROR", e.javaClass.name)
		return body(500, "INTERNAL_ERROR", "처리 중 오류가 발생했다")
	}

	private fun tooLarge(request: HttpServletRequest): ResponseEntity<Map<String, String>>? =
		if (request.getAttribute(BodyLimitFilter.EXCEEDED) != null) body(413, "PAYLOAD_TOO_LARGE", "요청 본문이 너무 크다") else null

	private fun body(status: Int, code: String, message: String) =
		ResponseEntity.status(status).body(mapOf("code" to code, "message" to message))
}
