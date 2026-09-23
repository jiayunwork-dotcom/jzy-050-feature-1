package com.geotech.bearing.web;

import com.geotech.bearing.layered.profile.LayeredProfileAlreadyExistsException;
import com.geotech.bearing.layered.profile.LayeredProfileNotFoundException;
import com.geotech.bearing.layered.validation.LayerValidationException;
import com.geotech.bearing.profile.ProfileAlreadyExistsException;
import com.geotech.bearing.profile.ProfileNotFoundException;
import com.geotech.bearing.validation.InvalidInputException;
import com.geotech.bearing.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * 全局异常 -> 结构化错误响应。所有非法输入都在承载力计算之前被拦下并带原因返回。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ErrorResponse> handleInvalid(InvalidInputException ex,
                                                       HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getReason(), ex.getMessage(), req);
    }

    /**
     * 分层剖面结构/某层指标非法：仍是 400，但额外带「第几层」（0 基层号），
     * 让调用方直接定位出问题的层。{@link LayerValidationException} 是
     * {@link InvalidInputException} 的子类，本处理器必须在其父类之前匹配。
     */
    @ExceptionHandler(LayerValidationException.class)
    public ResponseEntity<ErrorResponse> handleLayerInvalid(LayerValidationException ex,
                                                            HttpServletRequest req) {
        int layerIndex = ex.getLayerIndexZeroBased();
        Integer layerField = layerIndex == LayerValidationException.NO_LAYER ? null : layerIndex;
        return build(HttpStatus.BAD_REQUEST, ex.getReason(), ex.getMessage(), req, layerField);
    }

    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ProfileNotFoundException ex,
                                                        HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", ex.getMessage(), req);
    }

    @ExceptionHandler(ProfileAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ProfileAlreadyExistsException ex,
                                                        HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "PROFILE_ALREADY_EXISTS", ex.getMessage(), req);
    }

    /** 点名未登记的分层剖面：404，原因码与单层档区分。 */
    @ExceptionHandler(LayeredProfileNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleLayeredNotFound(LayeredProfileNotFoundException ex,
                                                               HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "LAYERED_PROFILE_NOT_FOUND", ex.getMessage(), req);
    }

    /** 分层剖面重名：409，原因码与单层档区分。 */
    @ExceptionHandler(LayeredProfileAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleLayeredConflict(LayeredProfileAlreadyExistsException ex,
                                                               HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "LAYERED_PROFILE_ALREADY_EXISTS", ex.getMessage(), req);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败。");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, req);
    }

    @ExceptionHandler({ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex,
                                                          HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), req);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "PARAMETER_MISSING",
                "缺少请求参数：" + ex.getParameterName() + "。", req);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "PARAMETER_TYPE_MISMATCH",
                "参数 " + ex.getName() + " 类型不正确：" + ex.getValue() + "。", req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_JSON",
                "请求体不是合法 JSON 或字段类型不正确。", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String reason,
                                                String message, HttpServletRequest req) {
        return build(status, reason, message, req, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String reason,
                                                String message, HttpServletRequest req,
                                                Integer layerIndex) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), reason, message, req.getRequestURI(), layerIndex);
        return ResponseEntity.status(status).body(body);
    }
}
