package io.github.ryancamus.clinic.framework.web;

import io.github.ryancamus.clinic.common.exception.BizException;
import io.github.ryancamus.clinic.common.exception.CommonErrorCode;
import io.github.ryancamus.clinic.common.exception.ErrorCode;
import io.github.ryancamus.clinic.common.result.R;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把各类异常转换成统一返回体，并给出对应的 HTTP 状态码。
 * <p>
 * 只有未预期的异常才记录堆栈；业务异常和参数错误属于正常分支，记一行 warn 即可。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        // 业务拒绝是正常分支（如已约满），高峰期数量巨大，只在 debug 级别记录
        log.debug("业务异常 code={} msg={}", e.getErrorCode().code(), e.getMessage());
        return build(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = fieldError == null
                ? CommonErrorCode.BAD_REQUEST.message()
                : fieldError.getField() + " " + fieldError.getDefaultMessage();
        return build(CommonErrorCode.BAD_REQUEST, msg);
    }

    @ExceptionHandler({ConstraintViolationException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<R<Void>> handleBadRequest(Exception e) {
        log.warn("参数错误: {}", e.getMessage());
        return build(CommonErrorCode.BAD_REQUEST, CommonErrorCode.BAD_REQUEST.message());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthentication(AuthenticationException e) {
        return build(CommonErrorCode.UNAUTHORIZED, CommonErrorCode.UNAUTHORIZED.message());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(AccessDeniedException e) {
        return build(CommonErrorCode.FORBIDDEN, CommonErrorCode.FORBIDDEN.message());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<R<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
        return build(CommonErrorCode.CONFLICT, CommonErrorCode.CONFLICT.message());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<R<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", e.getMostSpecificCause().getMessage());
        return build(CommonErrorCode.CONFLICT, "数据已存在或重复提交");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        return build(CommonErrorCode.NOT_FOUND, CommonErrorCode.NOT_FOUND.message());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return build(CommonErrorCode.METHOD_NOT_ALLOWED, CommonErrorCode.METHOD_NOT_ALLOWED.message());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return build(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE, CommonErrorCode.UNSUPPORTED_MEDIA_TYPE.message());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpected(Exception e) {
        log.error("未预期的异常", e);
        return build(CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.message());
    }

    /** 请求属性：本次请求的业务错误码，供审计日志记录失败原因 */
    public static final String ERROR_CODE_ATTRIBUTE = "clinic.errorCode";

    private static ResponseEntity<R<Void>> build(ErrorCode code, String msg) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ERROR_CODE_ATTRIBUTE, code.code(), RequestAttributes.SCOPE_REQUEST);
        }
        return ResponseEntity.status(code.httpStatus()).body(R.fail(code, msg));
    }
}
