package com.careerai.backend.admin;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(basePackages="com.careerai.backend.admin")
public class AdminExceptionHandler {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> status(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",safe(e.getReason())));
    }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<?> badRequest(Exception e) {return ResponseEntity.badRequest().body(Map.of("message",e instanceof MethodArgumentNotValidException?"Проверьте обязательные поля и длину текста":safe(e.getMessage())));}
    @ExceptionHandler(java.time.DateTimeException.class)
    public ResponseEntity<?> invalidDate() {return ResponseEntity.badRequest().body(Map.of("message","Укажите существующую дату в формате ГГГГ-ММ-ДД"));}
    @ExceptionHandler({IllegalStateException.class,OptimisticLockingFailureException.class,DataIntegrityViolationException.class})
    public ResponseEntity<?> conflict(Exception e) {return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message","Операция конфликтует с текущим состоянием. Обновите данные; проверьте уникальность slug и допустимость действия."));}
    private static String safe(String message) {return message==null?"Некорректный запрос":message.substring(0,Math.min(300,message.length()));}
}
