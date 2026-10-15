package com.halty.gramalyzerbot.workspace;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;
import static com.halty.gramalyzerbot.workspace.Models.*;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final WorkspaceService workspace;
    public WorkspaceController(WorkspaceService workspace) {this.workspace=workspace;}
    @GetMapping("/health") public Map<String,Object> health() {return Map.of("ok",true,"demo",workspace.workspace().demo(),"backend","spring");}
    @GetMapping("/workspace") public Workspace workspace() {return workspace.workspace();}
    @PatchMapping("/channels/{id}") public Channel settings(@PathVariable String id, @RequestBody ObjectNode body) {return workspace.settings(id,body);}
    @PostMapping("/posts") public ResponseEntity<Post> create(@Valid @RequestBody PostInput input) {return ResponseEntity.status(201).body(workspace.save(null,input));}
    @PatchMapping("/posts/{id}") public Post update(@PathVariable String id, @Valid @RequestBody PostInput input) {return workspace.save(id,input);}
    @DeleteMapping("/posts/{id}") public ResponseEntity<Void> delete(@PathVariable String id) {workspace.delete(id); return ResponseEntity.noContent().build();}
    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(WorkspaceService.Problem.class) ResponseEntity<?> problem(WorkspaceService.Problem e) {return ResponseEntity.status(e.status).body(Map.of("error",e.getMessage()));}
        @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> invalid(MethodArgumentNotValidException e) {var error=e.getBindingResult().getFieldErrors().getFirst(); return ResponseEntity.badRequest().body(Map.of("error",error.getField()+": "+error.getDefaultMessage()));}
        @ExceptionHandler(HttpMessageNotReadableException.class) ResponseEntity<?> unreadable() {return ResponseEntity.badRequest().body(Map.of("error","The request contains an invalid value or date."));}
    }
}
