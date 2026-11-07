package com.myproject.agent;

import com.myproject.result.ResponseResult;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AgentController.class, com.myproject.rag.RagController.class})
public class AgentExceptionHandler {
    @ExceptionHandler(AgentException.class)
    public ResponseEntity<ResponseResult<Void>> agent(AgentException exception) {
        return ResponseEntity.status(exception.status()).body(ResponseResult.fail(exception.status(), exception.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResponseResult<Void>> malformed() {
        return ResponseEntity.badRequest().body(ResponseResult.fail(400, "请求JSON格式无效"));
    }
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ResponseResult<Void>> missing() {
        return ResponseEntity.badRequest().body(ResponseResult.fail(400, "请求缺少必填参数或文件"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseResult<Void>> unexpected() {
        return ResponseEntity.internalServerError().body(ResponseResult.fail(500, "健康助手暂不可用，请稍后重试"));
    }
}
